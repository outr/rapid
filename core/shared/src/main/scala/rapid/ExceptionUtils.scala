package rapid

import java.util.concurrent.{ExecutionException, TimeoutException, CancellationException}

/**
 * Utility object for common exception handling patterns across the codebase.
 */
object ExceptionUtils {
  
  /**
   * Unwraps ExecutionException to get the underlying cause.
   * This is commonly needed when dealing with Future.get() calls.
   */
  def unwrapExecutionException(e: Throwable): Throwable = e match {
    case ee: ExecutionException if ee.getCause != null => ee.getCause
    case other => other
  }
  
  /**
   * Handles exceptions from Future operations, unwrapping ExecutionException.
   * Rethrows the unwrapped exception.
   */
  def handleFutureException[T](block: => T): T = {
    try {
      block
    } catch {
      case e: ExecutionException => throw unwrapExecutionException(e)
      case e: Throwable => throw e
    }
  }
  
  /**
   * Handles exceptions from Future operations with timeout.
   * Returns None on timeout, rethrows unwrapped exceptions otherwise.
   */
  def handleFutureWithTimeout[T](block: => T): Option[T] = {
    try {
      Some(block)
    } catch {
      case _: TimeoutException => None
      case e: ExecutionException => throw unwrapExecutionException(e)
      case e: Throwable => throw e
    }
  }
  
  /**
   * Creates a CancellationException with a standard message.
   */
  def cancellationException(message: String = "Task was cancelled"): CancellationException = {
    new CancellationException(message)
  }
}