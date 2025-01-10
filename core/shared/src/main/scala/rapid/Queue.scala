package rapid

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

case class Queue[T](maxSize: Int) {
  private val q = new ConcurrentLinkedQueue[T]
  private val s = new AtomicInteger(0)

  def size: Int = s.get()

  def isEmpty: Boolean = size == 0

  def enqueue(value: T): Boolean = {
    var incremented = false
    s.updateAndGet((operand: Int) => {
      if (operand < maxSize) {
        incremented = true
        operand + 1
      } else {
        incremented = false
        operand
      }
    })
    if (incremented) {
      q.add(value)
    }
    incremented
  }

  def dequeue(): Opt[T] = {
    val o = Opt(q.poll())
    if (o.notEmpty) {
      s.decrementAndGet()
    }
    o
  }

  def remove(value: T): Boolean = {
    val removed = q.remove(value)
    if (removed) {
      s.decrementAndGet()
    }
    removed
  }
}
