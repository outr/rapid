// ParallelStreamProcessor.scala
package rapid

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import java.util.concurrent.ConcurrentLinkedQueue
import scala.annotation.tailrec

case class ParallelStreamProcessor[T, R](
                                          stream: ParallelStream[T, R],
                                          handle: R => Unit,
                                          complete: Int => Unit
                                        ) {
  // pullTask now comes straight off the Stream instance
  private val pullTask: Task[Pull[T]] = Stream.task(stream.stream)

  private val workQueue  = new BoundedMPMCQueue[(T, Int)](stream.maxBuffer)
  private val readyQueue = new ConcurrentLinkedQueue[(Int, Option[R])]()
  @volatile private var _total = -1

  // Feed the pull into the queue until empty
  pullTask.map { pull =>
    var idx = 0
    @tailrec
    def loadNext(): Unit = {
      pull.pull() match {
        case Some(t) =>
          var backoff = 1
          while (!workQueue.enqueue((t, idx))) {
            if (backoff < 1000) {
              // Exponential backoff with a cap
              backoff *= 2
              for (_ <- 0 until backoff) {
                // Busy-wait for a short time
              }
            } else {
              // If we've backed off a lot, yield to other threads
              Thread.`yield`()
            }
          }
          idx += 1
          loadNext()
        case None =>
          _total = idx
      }
    }
    loadNext()
  }.start()

  private val processedCount = new AtomicInteger(0)

  // Process the queue and feed into readyQueue
  {
    def processLoop(): Unit = {
      var backoff = 1

      @tailrec
      def loop(): Unit = {
        workQueue.dequeue().foreach { case (t, index) =>
          val result: Option[R] = stream.forge(t).sync()
          readyQueue.offer((index, result))
          processedCount.incrementAndGet()
          // Reset backoff on successful dequeue
          backoff = 1
        }

        if (_total != -1 && processedCount.get() >= _total) {
          ()  // done
        } else {
          if (backoff < 1000) {
            // Exponential backoff with a cap
            backoff *= 2
            for (_ <- 0 until backoff) {
              // Busy-wait for a short time
            }
          } else {
            // If we've backed off a lot, yield to other threads
            Thread.`yield`()
          }
          loop()
        }
      }

      loop()
    }

    (0 until stream.maxThreads).foreach { _ =>
      Task(processLoop())
        .handleError { t =>
          t.printStackTrace()
          throw t
        }
        .start()
    }
  }

  def total: Option[Int] =
    if (_total == -1) None else Some(_total)

  private def dequeueReady(): Opt[(Int, Option[R])] = {
    val item = readyQueue.poll()
    if (item != null) Opt.Value(item) else Opt.Empty
  }

  // Start the final ordering fiber
  Task {
    var backoff = 1
    handleNext(expected = 0, valueCounter = 0, backoff = backoff)
  }.start()

  private def handleNext(
                          expected: Int,
                          valueCounter: Int,
                          backoff: Int,
                          buffer: Map[Int, Option[R]] = Map.empty
                        ): Unit = {
    if (_total != -1 && expected >= _total) {
      complete(valueCounter)
    } else {
      val (updatedBuffer, gotNew) = dequeueReady() match {
        case Opt.Value((idx, res)) => (buffer + (idx -> res), true)
        case Opt.Empty             => (buffer, false)
      }
      updatedBuffer.get(expected) match {
        case Some(result) =>
          result.foreach(handle)
          val newBuf = updatedBuffer - expected
          // Reset backoff on success
          handleNext(expected + 1, valueCounter + 1, 1, newBuf)
        case None =>
          if (!gotNew) {
            val newBackoff = if (backoff < 1000) {
              // Exponential backoff with a cap
              val b = backoff * 2
              for (_ <- 0 until b) {
                // Busy-wait for a short time
              }
              b
            } else {
              // If we've backed off a lot, yield to other threads
              Thread.`yield`()
              backoff
            }
            handleNext(expected, valueCounter, newBackoff, updatedBuffer)
          } else {
            // Reset backoff if we got new data
            handleNext(expected, valueCounter, 1, updatedBuffer)
          }
      }
    }
  }
}
