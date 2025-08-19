package rapid.util

import java.util.concurrent.atomic.AtomicBoolean

object Probe {
  private val fired = new AtomicBoolean(false)
  def once(tag: String): Boolean = fired.compareAndSet(false, true)
}