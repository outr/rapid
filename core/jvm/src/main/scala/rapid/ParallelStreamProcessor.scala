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
  private val pullTask: Task[Pull[T]] = stream.stream.task
  private val workQueue = new BoundedMPMCQueue[(T, Int)](stream.maxBuffer)
  private val readyQueue = new ConcurrentLinkedQueue[ReadyCell]()
  @volatile private var _total = -1
  @volatile private var failure: Option[Throwable] = None
  private val processedCount = new AtomicInteger(0)

  // Dedicated executor to run producer, workers, and aggregator to avoid starving the global Rapid executor
  private val localExecutor = new java.util.concurrent.ThreadPoolExecutor(
    stream.maxThreads,
    stream.maxThreads,
    60L,
    java.util.concurrent.TimeUnit.SECONDS,
    new java.util.concurrent.LinkedBlockingQueue[Runnable](),
    new java.util.concurrent.ThreadFactory {
      override def newThread(r: Runnable): Thread = {
        val t = new Thread(r, "rapid-parallel-forge")
        t.setDaemon(true)
        t
      }
    }
  )

  private final class ReadyCell(val index: Int, val value: AnyRef)

  private val NullSentinel: AnyRef = new Object()

  // feed producer (runs on localExecutor)
  localExecutor.execute(new Runnable {
    override def run(): Unit = {
      try {
        val pull = pullTask.sync()
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
            val elem = batch(i)
            var ok = workQueue.enqueue(elem)
            while (!ok && failure.isEmpty) {
              backoff = spinOrNap(backoff)
              ok = workQueue.enqueue(elem)
            }
            if (ok) {
              backoff = 1
              i += 1
            }
          }
          n = 0
        }

        @tailrec
        def loadNext(current: Pull[T], stack: java.util.ArrayDeque[Pull[T]]): Unit = {
          if (failure.nonEmpty) {
            if (n > 0) flushBatch()
            _total = idx
          } else {
            current.pull.sync() match {
              case Step.Emit(t) =>
                batch(n) = (t, idx)
                n += 1
                idx += 1
                if (n == batchSize) flushBatch()
                loadNext(current, stack)
              case Step.Skip =>
                loadNext(current, stack)
              case Step.Stop =>
                if (!stack.isEmpty) loadNext(stack.pop(), stack)
                else {
                  if (n > 0) flushBatch()
                  _total = idx
                }
              case Step.Concat(inner) =>
                stack.push(current)
                loadNext(inner, stack)
            }
          }
        }

        try loadNext(pull, new java.util.ArrayDeque[Pull[T]]())
        finally try pull.close.sync() catch {
          case _: Throwable => ()
        }
      } catch {
        case t: Throwable => failure = Some(t)
      }
    }
  })

  // workers (poll on localExecutor and execute forge on the same localExecutor)
  private def processLoop(): Unit = {
    var backoff = 1

    @tailrec
    def loop(): Unit = {
      if (failure.nonEmpty) ()
      else {
        val item = workQueue.dequeue()
        if (item.isDefined) {
          val (t, index) = item.get
          // Execute forge synchronously on local worker to respect maxThreads concurrency cap
          try {
            val opt = stream.forge(t).sync()
            val v: AnyRef = opt match {
              case Some(r) => r.asInstanceOf[AnyRef]
              case None => NullSentinel
            }
            readyQueue.offer(new ReadyCell(index, v))
            processedCount.incrementAndGet()
          } catch {
            case th: Throwable => failure = Some(th)
          }
          backoff = 1
        } else {
          if (_total != -1 && processedCount.get() >= _total) ()
          else if (failure.nonEmpty) ()
          else {
            if (backoff < 1024) {
              backoff = backoff << 1; Thread.onSpinWait()
            }
            else java.util.concurrent.locks.LockSupport.parkNanos(1_000L)
          }
        }
        if (failure.isEmpty && !(_total != -1 && processedCount.get() >= _total)) loop()
      }
    }

    loop()
  }

  (0 until stream.maxThreads).foreach { _ => localExecutor.execute(new Runnable {
    override def run(): Unit = processLoop()
  })
  }

  def total: Option[Int] = if (_total == -1) None else Some(_total)

  // final ordering/aggregation (runs on localExecutor)
  localExecutor.execute(new Runnable {
    override def run(): Unit = {
      val buffer = new java.util.HashMap[Int, AnyRef]()
      var expected = 0
      var delivered = 0
      var backoff = 1

      def done: Boolean = _total != -1 && expected >= _total

      while (failure.isEmpty && !done) {
        val polled = readyQueue.poll()
        if (polled != null) {
          buffer.put(polled.index, polled.value)
          backoff = 1
        } else {
          if (backoff < 1024) {
            backoff = backoff << 1; java.lang.Thread.onSpinWait()
          }
          else java.util.concurrent.locks.LockSupport.parkNanos(1_000L)
        }

        var present = buffer.get(expected)
        while (failure.isEmpty && present != null) {
          if (present ne NullSentinel) {
            handle(present.asInstanceOf[R]); delivered += 1
          }
          buffer.remove(expected)
          expected += 1
          backoff = 1
          present = buffer.get(expected)
        }
      }

      try {
        failure match {
          case Some(t) => onError(t)
          case None => complete(delivered)
        }
      } finally {
        try {
          localExecutor.shutdownNow()
        } catch {
          case _: Throwable => ()
        }
      }
    }
  })
}

object ParallelStreamProcessor {}
