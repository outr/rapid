package rapid

import java.util.concurrent.locks.LockSupport
import java.util.concurrent.atomic.AtomicReference
import scala.util.{Try, Success, Failure}

/**
 * A parking mechanism for fibers that allows a task to yield its thread
 * to other tasks while waiting for a result.
 * 
 * This enables cooperative multitasking where blocked fibers don't consume
 * OS threads, allowing for millions of concurrent fibers.
 * 
 * @tparam T The type of the result
 */
final class FiberParker[T] {
  // The result, once available
  private val result = new AtomicReference[Option[Try[T]]](None)
  
  // The parked thread waiting for the result
  @volatile private var parkedThread: Thread = _
  
  // For debugging and monitoring
  private val parkTime = new AtomicReference[Long](0L)
  
  /**
   * Park the current thread until a result is available.
   * 
   * This method will:
   * 1. Check if result is already available (fast path)
   * 2. Park the current thread if no result yet
   * 3. Handle spurious wakeups by re-checking the result
   * 
   * @return The result value
   * @throws Exception if the computation failed
   */
  def park(): T = {
    // Fast path - check if result already available
    result.get() match {
      case Some(Success(value)) => return value
      case Some(Failure(error)) => throw error
      case None => // Continue to slow path
    }
    
    // Record the thread that will be parked
    parkedThread = Thread.currentThread()
    parkTime.set(System.nanoTime())
    
    // Park until result is available
    while (result.get().isEmpty) {
      // Park this thread
      LockSupport.park(this)
      
      // Thread was unparked - check if it was spurious
      // The loop will re-check the result condition
    }
    
    // Result is now available
    val parkDuration = System.nanoTime() - parkTime.get()
    // Could track parking statistics here if needed
    
    result.get() match {
      case Some(Success(value)) => value
      case Some(Failure(error)) => throw error
      case None => 
        // This shouldn't happen but handle gracefully
        throw new IllegalStateException("Parker unparked without result")
    }
  }
  
  /**
   * Unpark the waiting thread with a successful result.
   * 
   * @param value The result value
   */
  def unparkSuccess(value: T): Unit = {
    unpark(Success(value))
  }
  
  /**
   * Unpark the waiting thread with a failure.
   * 
   * @param error The error that occurred
   */
  def unparkFailure(error: Throwable): Unit = {
    unpark(Failure(error))
  }
  
  /**
   * Internal unpark implementation.
   * 
   * @param tryResult The result to deliver
   */
  private def unpark(tryResult: Try[T]): Unit = {
    // Set the result first (memory fence via AtomicReference)
    if (!result.compareAndSet(None, Some(tryResult))) {
      // Result was already set - this is a programming error
      // Log warning but don't throw to avoid disrupting the unparker
      System.err.println(s"Warning: FiberParker.unpark called multiple times")
      return
    }
    
    // Wake up the parked thread if any
    val thread = parkedThread
    if (thread != null) {
      LockSupport.unpark(thread)
    }
    // If no thread is parked yet, the result is already set
    // and park() will see it on the fast path
  }
  
  /**
   * Check if a thread is currently parked waiting for a result.
   * 
   * @return true if a thread is parked
   */
  def isParked: Boolean = parkedThread != null && result.get().isEmpty
  
  /**
   * Check if a result is available.
   * 
   * @return true if a result has been set
   */
  def hasResult: Boolean = result.get().isDefined
  
  /**
   * Get the parked thread, if any.
   * 
   * @return The parked thread or null
   */
  def getParkedThread: Thread = parkedThread
}