package rapid

import java.util.concurrent.{ExecutionException, TimeoutException, TimeUnit}
import scala.concurrent.duration.FiniteDuration
import scala.util.Try

/**
 * Abstract base trait for Fiber implementations that provides common functionality
 * for exception handling, timeout management, and cancellation patterns.
 */
abstract class AbstractFiber[Return] extends Fiber[Return] with Blockable[Return] {
  
  /**
   * Unwraps ExecutionException to get the underlying cause.
   * This is a common pattern across all Fiber implementations.
   */
  protected def unwrapExecutionException(e: Throwable): Throwable = e match {
    case ee: ExecutionException if ee.getCause != null => ee.getCause
    case other => other
  }
  
  /**
   * Common implementation for await with timeout.
   * Subclasses should implement doAwait to provide the actual waiting mechanism.
   */
  override def await(duration: FiniteDuration): Option[Return] = {
    try {
      doAwait(duration.toMillis, TimeUnit.MILLISECONDS)
    } catch {
      case _: TimeoutException => None
      case e: ExecutionException => throw unwrapExecutionException(e)
      case e: Throwable => throw e
    }
  }
  
  /**
   * Template method for subclasses to implement their specific await logic.
   * Should return Some(result) if completed within timeout, None otherwise.
   */
  protected def doAwait(timeout: Long, unit: TimeUnit): Option[Return]
  
  /**
   * Common sync implementation with ExecutionException unwrapping.
   * Subclasses should implement doSync for their specific synchronization logic.
   */
  override def sync(): Return = {
    try {
      doSync()
    } catch {
      case e: ExecutionException => throw unwrapExecutionException(e)
      case e: Throwable => throw e
    }
  }
  
  /**
   * Template method for subclasses to implement their specific sync logic.
   */
  protected def doSync(): Return
  
  /**
   * Common cancellation support with volatile flag.
   * Subclasses can override if they need different cancellation behavior.
   */
  @volatile protected var cancelled: Boolean = false
  
  /**
   * Default cancel implementation using the volatile flag.
   * Subclasses should override performCancellation to implement actual cancellation.
   */
  override def cancel: Task[Boolean] = Task {
    if (!cancelled) {
      cancelled = true
      performCancellation()
      true
    } else {
      false
    }
  }
  
  /**
   * Template method for subclasses to implement their specific cancellation logic.
   * Called when cancel is invoked and the fiber hasn't been cancelled yet.
   */
  protected def performCancellation(): Unit
}