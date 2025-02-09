package rapid

import java.util.concurrent.atomic.AtomicInteger

class LockFreeQueue[A](capacity: Int) {
  private val buffer = new Array[AnyRef](capacity)
  private val head = new AtomicInteger(0)
  private val tail = new AtomicInteger(0)

  def enqueue(value: A): Boolean = {
    val currentTail = tail.get()
    val nextTail = (currentTail + 1) % capacity
    if (nextTail != head.get()) {
      buffer(currentTail) = value.asInstanceOf[AnyRef]
      tail.set(nextTail)
      true
    } else false // Queue full
  }

  def dequeue(): Opt[A] = {
    var currentHead = head.get()
    while (true) {
      val currentTail = tail.get()
      if (currentHead == currentTail) return Opt.Empty

      // Try to claim the current head position atomically
      if (head.compareAndSet(currentHead, (currentHead + 1) % capacity)) {
        val value = buffer(currentHead).asInstanceOf[A]
        buffer(currentHead) = null // Avoid memory leaks
        return Opt.Value(value)
      }
      // If CAS failed, update currentHead and retry
      currentHead = head.get()
    }
    // Should never reach here
    Opt.Empty
  }
}