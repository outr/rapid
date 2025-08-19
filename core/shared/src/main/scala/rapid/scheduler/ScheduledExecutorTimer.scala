package rapid.scheduler

import java.util.concurrent._
import scala.concurrent.duration._

final class ScheduledExecutorTimer(threadName: String = "rapid-timer") extends Timer {
  private val exec = {
    val tf = new ThreadFactory {
      private val backing = Executors.defaultThreadFactory()
      override def newThread(r: Runnable): Thread = {
        val t = backing.newThread(r)
        t.setDaemon(true)
        t.setName(s"$threadName-${t.getId}")
        t
      }
    }
    val e = new ScheduledThreadPoolExecutor(1, tf)
    e.setRemoveOnCancelPolicy(true) // avoid leak on cancel
    e
  }

  override def schedule(delay: FiniteDuration)(r: Runnable): CancelToken = {
    val fut = exec.schedule(r, delay.toNanos, TimeUnit.NANOSECONDS)
    () => fut.cancel(false) // idempotent, non-blocking
  }

  override def shutdown(): Unit = exec.shutdown()
}