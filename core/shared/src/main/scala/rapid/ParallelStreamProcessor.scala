// ParallelStreamProcessor.scala
package rapid

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import java.util.concurrent.ConcurrentLinkedQueue
import scala.annotation.tailrec
import scala.collection.mutable

case class ParallelStreamProcessor[T, R](stream: ParallelStream[T, R],
                                         handle: R => Unit,
                                         complete: Int => Unit,
                                         onError: Throwable => Unit) {
  private val pullTask: Task[Pull[T]] = Stream.task(stream.stream)
  private val workQueue = new BoundedMPMCQueue[(T, Int)](stream.maxBuffer)
  private val readyQueue = new ConcurrentLinkedQueue[(Int, Option[R])]()
  @volatile private var _total = -1
  @volatile private var failure: Option[Throwable] = None
  private val processedCount = new AtomicInteger(0)

  // feed producer
  pullTask.map { pull =>
    var idx = 0
    val batchSize = 64
    val batch = new Array[(T, Int)](batchSize)
    var n = 0

    def spinOrNap(backoff: Int): Int = {
      if (backoff < 1024) {
        val b = backoff << 1
        java.lang.Thread.onSpinWait()
        b
      } else {
        java.util.concurrent.locks.LockSupport.parkNanos(1_000L)
        backoff
      }
    }

    def flushBatch(): Unit = {
      var i = 0
      var backoff = 1
      while (i < n && failure.isEmpty) {
        var ok = workQueue.enqueue(batch(i))
        while (!ok && failure.isEmpty) {
          backoff = spinOrNap(backoff)
          ok = workQueue.enqueue(batch(i))
        }
        if (ok) {
          backoff = 1
          i += 1
        }
      }
      n = 0
    }

    @scala.annotation.tailrec
    def loadNext(): Unit = {
      if (failure.nonEmpty) {
        if (n > 0) flushBatch()
        _total = idx
      } else {
        pull.pull() match {
          case Some(t) =>
            batch(n) = (t, idx)
            n += 1
            idx += 1
            if (n == batchSize) flushBatch()
            loadNext()
          case None =>
            if (n > 0) flushBatch()
            _total = idx
        }
      }
    }

    loadNext()
  }.start()

  // workers
  {
    def processLoop(): Unit = {
      var backoff = 1
      @scala.annotation.tailrec
      def loop(): Unit = {
        if (failure.nonEmpty) ()
        else {
          workQueue.dequeue().foreach { case (t, index) =>
            try {
              val result = stream.forge(t).sync()
              readyQueue.offer((index, result))
              processedCount.incrementAndGet()
              backoff = 1
            } catch {
              case th: Throwable =>
                failure = Some(th)
            }
          }
          if (_total != -1 && processedCount.get() >= _total) ()
          else if (failure.nonEmpty) ()
          else {
            if (backoff < 1024) {
              backoff = backoff << 1
              Thread.onSpinWait()
            }
            else java.util.concurrent.locks.LockSupport.parkNanos(1_000L)
            loop()
          }
        }
      }
      loop()
    }
    (0 until stream.maxThreads).foreach { _ =>
      Task(processLoop()).start()
    }
  }

  def total: Option[Int] = if (_total == -1) None else Some(_total)

  // final ordering fiber (counts only Some, propagates failure)
  Task {
    val buffer = mutable.HashMap.empty[Int, Option[R]]
    var expected = 0
    var delivered = 0
    var backoff = 1

    def done =
      _total != -1 && expected >= _total

    while (failure.isEmpty && !done) {
      val polled = readyQueue.poll()
      if (polled != null) {
        val (idx, res) = polled
        buffer.put(idx, res)
        backoff = 1
      } else {
        if (backoff < 1024) {
          backoff = backoff << 1
          Thread.onSpinWait()
        }
        else java.util.concurrent.locks.LockSupport.parkNanos(1_000L)
      }

      var found = buffer.get(expected)
      while (failure.isEmpty && found.isDefined) {
        found.get match {
          case Some(v) =>
            handle(v)
            delivered += 1
          case None =>
            ()
        }
        buffer.remove(expected)
        expected += 1
        backoff = 1
        found = buffer.get(expected)
      }
    }

    failure match {
      case Some(t) => onError(t)
      case None    => complete(delivered)
    }
  }.start()
}
