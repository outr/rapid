package rapid.concurrency

import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.duration.{DurationInt, FiniteDuration}

object VirtualThreadConcurrencyManager extends ConcurrencyManager {
  private val counter = new AtomicLong(0L)
  private val zero = 0.seconds

  override def schedule(delay: FiniteDuration,
                        execution: TaskExecution[_]): Unit = {
    val thread = Thread
      .ofVirtual()
      .name(s"rapid-${counter.incrementAndGet()}")
      .start(() => {
        try {
          if (!execution.cancelled) {
            // Use interruptible sleep that can be cancelled
            val startTime = System.currentTimeMillis()
            val delayMillis = delay.toMillis

            while (!execution.cancelled && (System.currentTimeMillis() - startTime) < delayMillis) {
              Thread.sleep(1) // Small sleep to allow cancellation checks
            }
          }
          if (!execution.cancelled) {
            execution.execute(sync = false)
          }
        } catch {
          case _: InterruptedException => // Thread was interrupted, ignore
        }
      })

    // Store the thread reference so we can interrupt it if needed
    // For now, we'll rely on the cancellation flag checks
  }

  override def fire(execution: TaskExecution[_]): Unit = schedule(zero, execution)
}
