package rapid.runtime

import java.util.concurrent.atomic.{AtomicLong, AtomicReferenceArray}

/** Very small MPSC ring buffer for (fiber, cont) pairs. */
final class ReadyQueue(capacityPow2: Int = 1 << 20) { // 1M slots by default
  private val cap = {
    require((capacityPow2 & (capacityPow2 - 1)) == 0, "capacity must be power-of-two")
    capacityPow2
  }
  private val mask = cap - 1

  private final case class Item(fiber: AnyRef, cont: AnyRef)
  private val buf = new AtomicReferenceArray[Item](cap)
  private val head = new AtomicLong(0L) // consumer
  private val tail = new AtomicLong(0L) // producer(s)

  /** MPSC offer; non-blocking, returns false if full. */
  def offer(fiber: AnyRef, cont: AnyRef): Boolean = {
    var spins = 0
    var done = false
    while (!done) {
      val t = tail.get()
      val h = head.get()
      if ((t - h) >= cap) return false
      if (tail.compareAndSet(t, t + 1)) {
        buf.set((t & mask).toInt, Item(fiber, cont))
        return true
      } else {
        // backoff without recursion
        if (spins < 64) java.lang.Thread.onSpinWait() else Thread.`yield`()
        spins += 1
      }
    }
    false
  }

  /** Drain up to `max` items to the provided lambda. Returns drained count. */
  def drain(max: Int)(f: (AnyRef, AnyRef) => Unit): Int = {
    var n = 0
    var h = head.get()
    val t = tail.get()
    var available = (t - h).toInt
    if (available <= 0) return 0
    val limit = Math.min(available, max)
    var i = 0
    while (i < limit) {
      val idx = ((h + i) & mask).toInt
      val it = buf.getAndSet(idx, null)
      if (it != null) { f(it.fiber, it.cont); n += 1 }
      i += 1
    }
    head.lazySet(h + n)
    n
  }
}