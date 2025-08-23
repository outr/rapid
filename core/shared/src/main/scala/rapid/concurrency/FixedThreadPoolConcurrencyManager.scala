package rapid.concurrency

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.{Executors, ScheduledExecutorService, ThreadFactory, TimeUnit}
import scala.concurrent.duration.FiniteDuration
import scala.util.Try

object FixedThreadPoolConcurrencyManager extends ConcurrencyManager {
  private val counter = new AtomicLong(0L)

  private lazy val threadFactory = new ThreadFactory {
    override def newThread(r: Runnable): Thread = {
      val thread = new Thread(r)
      thread.setName(s"rapid-ft-${counter.incrementAndGet()}")
      thread.setDaemon(true)
      thread
    }
  }

  private lazy val executor = Executors.newFixedThreadPool(
    math.max(Runtime.getRuntime.availableProcessors(), 4),
    threadFactory
  )

  private lazy val scheduledExecutor: ScheduledExecutorService = Executors.newScheduledThreadPool(
    math.max(Runtime.getRuntime.availableProcessors() / 2, 2),
    new ThreadFactory {
      override def newThread(r: Runnable): Thread = {
        val thread = new Thread(r)
        thread.setName(s"rapid-scheduler-${counter.incrementAndGet()}")
        thread.setDaemon(true)
        thread
      }
    }
  )

  override def schedule(delay: FiniteDuration, execution: TaskExecution[_]): Unit = {
    scheduledExecutor.schedule(() => {
      Try {
        // Check for cancellation before executing the scheduled task
        if (!execution.cancelled) {
          execution.async()
        }
      }
    }, delay.toMillis, TimeUnit.MILLISECONDS)
  }

  override def fire(execution: TaskExecution[_]): Unit = executor.submit(() => Try(execution.execute(sync = false)))
}
