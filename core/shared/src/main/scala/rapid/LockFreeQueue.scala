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
    val currentHead = head.get()
    if (currentHead == tail.get()) Opt.Empty // Queue empty
    else {
      val value = buffer(currentHead).asInstanceOf[A]
      buffer(currentHead) = null // Avoid memory leaks
      head.set((currentHead + 1) % capacity)
      Opt.Value(value)
    }
  }
}