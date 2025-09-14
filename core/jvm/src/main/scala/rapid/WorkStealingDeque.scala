package rapid

import java.util.concurrent.atomic.{AtomicLong, AtomicReferenceArray}

/**
 * Chase-Lev work-stealing deque implementation.
 * 
 * This is a lock-free deque designed for the work-stealing pattern where:
 * - A single owner thread pushes and pops from one end (top)
 * - Multiple thief threads steal from the other end (bottom)
 * 
 * Based on the paper "Dynamic Circular Work-Stealing Deque" by Chase and Lev.
 * 
 * @param capacity The maximum number of elements (must be a power of 2)
 */
final class WorkStealingDeque[T](capacity: Int) {
  require((capacity & (capacity - 1)) == 0, "Capacity must be a power of 2")
  require(capacity > 0, "Capacity must be positive")
  
  private val mask = capacity - 1
  private val tasks = new AtomicReferenceArray[T](capacity)
  
  // Top is modified only by the owner thread (no need for atomic)
  @volatile private var top = 0L
  
  // Bottom is modified by stealing threads (needs to be atomic)
  private val bottom = new AtomicLong(0L)
  
  /**
   * Push a task onto the deque (owner thread only).
   * 
   * @param task The task to push
   * @return true if successful, false if the deque is full
   */
  def push(task: T): Boolean = {
    val t = top
    val b = bottom.get()
    val size = t - b
    
    if (size >= capacity - 1) {
      // Queue is full
      return false
    }
    
    tasks.set((t & mask).toInt, task)
    // Memory fence to ensure the task is visible before incrementing top
    top = t + 1
    true
  }
  
  /**
   * Pop a task from the deque (owner thread only).
   * 
   * @return Some(task) if available, None if empty
   */
  def poll(): Option[T] = {
    var t = top - 1
    top = t
    val b = bottom.get()
    
    if (t < b) {
      // Queue is empty
      top = b
      return None
    }
    
    val task = tasks.get((t & mask).toInt)
    
    if (t > b) {
      // More than one element, no contention possible
      return Option(task)
    }
    
    // Exactly one element - potential race with stealers
    // Try to claim it by incrementing bottom
    if (!bottom.compareAndSet(b, b + 1)) {
      // Lost the race to a stealer
      top = b + 1
      return None
    }
    
    top = b + 1
    Option(task)
  }
  
  /**
   * Steal a task from the deque (thief threads).
   * 
   * @return Some(task) if successful, None if empty or contention
   */
  def steal(): Option[T] = {
    var b = bottom.get()
    val t = top
    
    if (b >= t) {
      // Queue is empty
      return None
    }
    
    val task = tasks.get((b & mask).toInt)
    
    // Try to claim the task by incrementing bottom
    if (!bottom.compareAndSet(b, b + 1)) {
      // Lost the race to another stealer or the owner
      return None
    }
    
    Option(task)
  }
  
  /**
   * Check if the deque is empty.
   * 
   * @return true if empty, false otherwise
   */
  def isEmpty: Boolean = {
    val b = bottom.get()
    val t = top
    b >= t
  }
  
  /**
   * Get the current size of the deque.
   * Note: This is an approximation due to concurrent modifications.
   * 
   * @return The approximate number of elements
   */
  def size: Int = {
    val b = bottom.get()
    val t = top
    val s = t - b
    math.max(0, s.toInt)
  }
}