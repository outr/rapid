package rapid

import java.util.ArrayDeque
import scala.util.Try

/**
 * ExecutionContext holds the state for Task execution, enabling pausable/resumable
 * trampolines that avoid stack overflow from nested sync() calls.
 */
private[rapid] class ExecutionContext {
  /** Stack for trampoline execution */
  val stack = new ArrayDeque[Any]()
  
  /** Current result being passed between operations */
  var current: Any = ()
  
  /** Operation counter for optimization profiling */
  var operationCount: Int = 0
  
  /** Whether the context is in an error state */
  var hasError: Boolean = false
  
  /** Current error if hasError is true */
  var error: Throwable = null
  
  /** Continuations waiting for async completion */
  private val pendingContinuations = new java.util.concurrent.ConcurrentLinkedQueue[(Try[Any], ExecutionContext) => Unit]()
  
  /** Whether this context is currently running */
  @volatile var isRunning: Boolean = false
  
  /** Fields for continuation stealing - non-blocking with CompletableFuture */
  @volatile var stolen: Boolean = false
  @volatile var finalResult: Any = null
  @volatile var completionException: Throwable = null
  @volatile var completionFuture: java.util.concurrent.CompletableFuture[Any] = null
  
  def addContinuation(continuation: (Try[Any], ExecutionContext) => Unit): Unit = {
    pendingContinuations.offer(continuation)
  }
  
  def resumeWithResult(result: Try[Any]): Unit = {
    val continuation = pendingContinuations.poll()
    if (continuation != null) {
      continuation(result, this)
    }
  }
  
  def pause(): Unit = {
    isRunning = false
  }
  
  def resume(): Unit = {
    isRunning = true
  }
  
  def reset(): Unit = {
    stack.clear()
    current = ()
    operationCount = 0
    hasError = false
    error = null
    isRunning = false
    pendingContinuations.clear()
    stolen = false
    finalResult = null
    completionException = null
    completionFuture = null
  }
}