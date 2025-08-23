package rapid.concurrency

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.{Executors, ScheduledExecutorService, ThreadFactory, TimeUnit, Future => JavaFuture}
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

  override def schedule(delay: FiniteDuration, execution: TaskExecution[_]): Cancellable = {
    val future: JavaFuture[_] = scheduledExecutor.schedule(() => {
      Try {
        // Check for cancellation before executing the scheduled task
        if (!execution.cancelled) {
          execution.async()
        }
      }
    }, delay.toMillis, TimeUnit.MILLISECONDS)

    new Cancellable {
      override def cancel(): Boolean = {
        if (!future.isDone) {
          future.cancel(true)
        } else {
          false
        }
      }
      
      override def isCancelled: Boolean = future.isCancelled
    }
  }

  override def fire(execution: TaskExecution[_]): Cancellable = {
    val future: JavaFuture[_] = executor.submit(() => Try(execution.execute(sync = false)))
    
    new Cancellable {
      override def cancel(): Boolean = {
        if (!future.isDone) {
          future.cancel(true)
        } else {
          false
        }
      }
      
      override def isCancelled: Boolean = future.isCancelled
    }
  }
}
