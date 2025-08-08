package rapid

import java.util.concurrent.atomic.AtomicLong

/**
 * A bounded, lock-free, multi-producer/multi-consumer queue.
 *
 * Based on Dmitry Vyukov's algorithm.
 *
 * @param capacity the maximum number of elements that can be enqueued (must be > 0)
 */
final class BoundedMPMCQueue[A](capacity: Int) {
  require(capacity > 0, "Capacity must be positive.")

  // Each slot (cell) in the ring buffer carries a sequence number
  // and (once enqueued) the stored value.
  private final class Cell(initialSequence: Long) {
    // The sequence number for this cell.
    @volatile var sequence: Long = initialSequence
    // The stored value (null when empty). We disallow null items.
    @volatile var value: A = _
  }

  // Create an array (ring buffer) of cells.
  private val buffer = new Array[Cell](capacity)
  for (i <- 0 until capacity) {
    buffer(i) = new Cell(i.toLong)
  }

  // Head and tail counters are maintained as atomic longs.
  private val tail = new AtomicLong(0L)
  private val head = new AtomicLong(0L)

  /**
   * Enqueues an element.
   *
   * @param item the element to enqueue; must not be null.
   * @return true if the element was enqueued, or false if the queue is full.
   * @throws java.lang.NullPointerException if item is null.
   */
  def enqueue(item: A): Boolean = {
    if (item == null)
      throw new NullPointerException("BoundedMPMCQueue does not support null values.")

    while (true) {
      val currentTail = tail.get()
      val index = (currentTail % capacity).toInt
      val cell = buffer(index)
      // The expected sequence number for an empty cell is exactly equal to currentTail.
      val seq = cell.sequence
      val dif = seq - currentTail

      if (dif == 0) { // slot is available for writing
        // Try to claim this slot by advancing the tail.
        if (tail.compareAndSet(currentTail, currentTail + 1)) {
          // We have reserved the slot.
          cell.value = item
          // Publish the item by setting the sequence to (currentTail + 1).
          cell.sequence = currentTail + 1
          return true
        }
        // If CAS failed, another thread got here first; retry.
      } else if (dif < 0) {
        // If dif is negative, then the cell is not yet available for writing.
        // (That is, the producer has wrapped around too soon, meaning the queue is full.)
        return false
      } else {
        // The cell is not yet ready for use; yield and retry.
        Thread.`yield`()
      }
    }
    // Unreachable
    false
  }

  /**
   * Dequeues an element.
   *
   * @return Some(element) if an element was dequeued
   */
  def dequeue(): Option[A] = {
    while (true) {
      val currentHead = head.get()
      val index = (currentHead % capacity).toInt
      val cell = buffer(index)
      // The expected sequence number for a cell ready to be consumed is (currentHead + 1).
      val seq = cell.sequence
      val dif = seq - (currentHead + 1)

      if (dif == 0) { // slot is ready for reading
        // Try to claim the cell by advancing head.
        if (head.compareAndSet(currentHead, currentHead + 1)) {
          val result = cell.value
          // Mark the cell as available by setting its sequence to
          // (currentHead + capacity). This lets producers know that the slot can be reused.
          cell.sequence = currentHead + capacity
          return Some(result)
        }
      } else if (dif < 0) {
        // If dif is negative, the slot is not yet ready for reading => queue is empty.
        return None
      } else {
        // The cell is in the process of being updated; yield and retry.
        Thread.`yield`()
      }
    }
    // Unreachable
    None
  }
}
