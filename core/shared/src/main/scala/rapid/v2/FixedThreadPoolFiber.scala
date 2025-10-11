package rapid.v2

import java.util.concurrent.{CountDownLatch, LinkedBlockingQueue, ThreadFactory, ThreadPoolExecutor, TimeUnit}

case class FixedThreadPoolFiber[Return](task: Task[Return]) extends Fiber[Return] {
  // Minimal completion mechanics to avoid Future/Try allocations
  private[this] val done = new CountDownLatch(1)
  private[this] var result: Any = _
  private[this] var failure: Throwable = null

  // Schedule execution
  FixedThreadPoolFiber.executor.execute(() => {
    try {
      result = SynchronousFiber(task).sync()
    } catch {
      case t: Throwable => failure = t
    } finally {
      done.countDown()
    }
  })

  override def sync(): Return = {
    done.await()
    if (failure ne null) throw failure
    result.asInstanceOf[Return]
  }
}

object FixedThreadPoolFiber {
  private lazy val threadFactory = new ThreadFactory {
    override def newThread(r: Runnable): Thread = {
      val thread = new Thread(r, "rapid-v2-worker")
      thread.setDaemon(true)
      thread
    }
  }

  // ForkJoinPool reduces contention for many tiny tasks via per-worker deques
  private lazy val executor: ThreadPoolExecutor = new ThreadPoolExecutor(
    Math.max(Runtime.getRuntime.availableProcessors(), 4),
    Math.max(Runtime.getRuntime.availableProcessors(), 4),
    60L,
    TimeUnit.SECONDS,
    new LinkedBlockingQueue[Runnable](32768),
    threadFactory,
    new ThreadPoolExecutor.CallerRunsPolicy()
  )
}