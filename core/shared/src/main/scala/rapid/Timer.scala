package rapid

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Timer is used in Task to capture multi-execution timings. Very useful for finding bottlenecks in concurrent
 * applications.
 */
class Timer private {
  private[rapid] val _elapsed = new AtomicLong(0L)

  def elapsedNanos: Long = _elapsed.get()
  def elapsedMillis: Long = TimeUnit.NANOSECONDS.toMillis(elapsedNanos)
  def elapsedSeconds: Double = elapsedMillis / 1000.0

  def reset(): Task[Unit] = Task(_elapsed.set(0L))
}

object Timer {
  def apply(): Timer = new Timer
}