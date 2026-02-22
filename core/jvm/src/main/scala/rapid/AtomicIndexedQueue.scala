package rapid

import java.util.concurrent.locks.ReentrantLock
import scala.collection.mutable

class AtomicIndexedQueue[T](capacity: Int) {
  private val lock = new ReentrantLock()
  private val notEmpty = lock.newCondition()
  private val notFull = lock.newCondition()

  private val queue = mutable.TreeMap[Int, T]()
  private var nextIndex = 0

  def add(index: Int, element: T): Unit = {
    lock.lock()
    try {
      // Allow "next index" to bypass capacity restriction
      while (queue.size >= capacity && index != nextIndex) {
        notFull.await()
      }
      // Add the element to the queue
      queue.put(index, element)
      // Signal waiting threads
      notEmpty.signalAll()
    } finally {
      lock.unlock()
    }
  }

  def blockingPoll(): Option[T] = {
    lock.lock()
    try {
      // Wait until the next expected index is available
      while (!queue.contains(nextIndex)) {
        notEmpty.await()
      }
      // Retrieve and remove the next element
      val element = queue.remove(nextIndex)
      nextIndex += 1
      // Signal producers waiting to add
      notFull.signalAll()
      element
    } finally {
      lock.unlock()
    }
  }
}
