package rapid

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentLinkedQueue
import scala.annotation.tailrec

case class ParallelStreamProcessor[T, R](stream: ParallelStream[T, R],
                                         handle: R => Unit,
                                         complete: Int => Unit) {
  private val iteratorTask: Task[Iterator[T]] = Stream.task(stream.stream)

  private val workQueue = new BoundedMPMCQueue[(T, Int)](stream.maxBuffer)
  private val readyQueue = new ConcurrentLinkedQueue[(Int, Option[R])]()

  @volatile private var _total = -1

  // Feed the iterator into the queue until empty
  iteratorTask.map { iterator =>
    var counter = 0
    iterator.zipWithIndex.foreach { tuple =>
      while (!workQueue.enqueue(tuple)) {
        Thread.`yield`()
      }
      counter += 1
    }
    _total = counter
  }.start()


  private val processedCount = new AtomicInteger(0)

  // Process the queue and feed into ready
  {
    @tailrec
    def processLoop(): Unit = {
      val next = workQueue.dequeue()
      next.foreach { case (t, index) =>
        // Process the work item.
        val result: Option[R] = stream.forge(t).sync()
        // Enqueue the result into the unbounded ready queue.
        readyQueue.offer((index, result))
        processedCount.incrementAndGet()
      }
      if (_total != -1 && processedCount.get() >= _total) {
        // All work processed; exit.
      } else {
        Thread.`yield`()
        processLoop()
      }
    }
    // Start several processing fibers (up to maxThreads).
    (0 until stream.maxThreads).foreach { _ =>
      Task(processLoop())
        .handleError { throwable =>
          throwable.printStackTrace()
          throw throwable
        }
        .start()
    }
  }

  def total: Option[Int] = if (_total == -1) None else Some(_total)

  private def dequeueReady(): Opt[(Int, Option[R])] = {
    val item = readyQueue.poll()
    if (item != null) Opt.Value(item) else Opt.Empty
  }

  Task(handleNext(expected = 0, valueCounter = 0)).start()

  @tailrec
  private def handleNext(expected: Int, valueCounter: Int,
                         buffer: Map[Int, Option[R]] = Map.empty): Unit = {
    // When we know the total number of work items and have handled them all, finish.
    if (_total != -1 && expected >= _total) {
      complete(valueCounter)
    } else {
      val (updatedBuffer, gotNew) = dequeueReady() match {
        case Opt.Value((idx, res)) => (buffer + (idx -> res), true)
        case Opt.Empty             => (buffer, false)
      }
      updatedBuffer.get(expected) match {
        case Some(result) =>
          // Process the result for the expected index.
          result match {
            case Some(v) => handle(v)
            case None    => // If result is None, nothing to handle.
          }
          // Remove the processed item and move on to the next expected index.
          val newBuffer = updatedBuffer - expected
          handleNext(expected + 1, valueCounter + 1, newBuffer)
        case None =>
          // If no new item for the expected index was dequeued, yield a bit and try again.
          if (!gotNew) Thread.`yield`()
          handleNext(expected, valueCounter, updatedBuffer)
      }
    }
  }
}
