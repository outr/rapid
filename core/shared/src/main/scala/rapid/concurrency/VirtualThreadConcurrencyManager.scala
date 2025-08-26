package rapid.concurrency

import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}
import scala.concurrent.duration.{DurationInt, FiniteDuration}

object VirtualThreadConcurrencyManager extends ConcurrencyManager {
  private val counter = new AtomicLong(0L)
  private val zero = 0.seconds

  override def schedule(delay: FiniteDuration,
                        execution: TaskExecution[_]): Unit = {
    val cancelled = new AtomicBoolean(false)
    val thread = Thread
      .ofVirtual()
      .name(s"rapid-${counter.incrementAndGet()}")
      .start(() => {
        try {
          if (!cancelled.get() && !execution.cancelled) {
            // Use interruptible sleep that can be cancelled
            val startTime = System.currentTimeMillis()
            val delayMillis = delay.toMillis

            while (!cancelled.get() && !execution.cancelled && (System.currentTimeMillis() - startTime) < delayMillis) {
              Thread.sleep(1) // Small sleep to allow cancellation checks
            }
          }
          if (!cancelled.get() && !execution.cancelled) {
            execution.execute(sync = false)
          }
        } catch {
          case _: InterruptedException => // Thread was interrupted, ignore
        }
      })
  }

  override def fire(execution: TaskExecution[_]): Unit = schedule(zero, execution)
}
