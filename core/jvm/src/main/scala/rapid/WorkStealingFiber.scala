package rapid

import java.util.concurrent.{CompletableFuture, TimeUnit}
import scala.concurrent.duration.FiniteDuration
import scala.util.{Try, Success, Failure}

/**
 * A fiber implementation that uses work-stealing for efficient task execution.
 * 
 * Key features:
 * - Non-blocking execution via fiber parking
 * - Work-stealing for load balancing
 * - Integrates with TimerWheel for timed operations
 * 
 * @param task The task to execute
 */
class WorkStealingFiber[Return](val task: Task[Return]) extends Fiber[Return] with Blockable[Return] {
  
  // Unique fiber ID
  override val id: Long = FiberIdGenerator.nextId()
  
  // The future representing this fiber's completion
  private lazy val future: CompletableFuture[Return] = WorkStealingPool.submit(task)
  
  /**
   * Execute the task synchronously.
   * 
   * If called from a worker thread, this will execute directly to avoid
   * recursion. Otherwise, it blocks traditionally.
   */
  override def sync(): Return = {
    if (WorkStealingPool.isWorkerThread) {
      // We're already in a worker thread - execute directly to avoid recursion
      // This prevents deadlock when tasks call sync() on other tasks
      task.sync()
    } else {
      // External thread - must block traditionally
      try {
        future.get()
      } catch {
        case e: java.util.concurrent.ExecutionException =>
          throw e.getCause
        case e: Throwable =>
          throw e
      }
    }
  }
  
  /**
   * Cancel this fiber.
   */
  override def cancel: Task[Boolean] = Task {
    future.cancel(true)
  }
  
  /**
   * Wait for the task to complete with a timeout.
   */
  override def await(duration: FiniteDuration): Option[Return] = {
    try {
      val result = future.get(duration.toMillis, TimeUnit.MILLISECONDS)
      Some(result)
    } catch {
      case _: java.util.concurrent.TimeoutException => None
      case e: java.util.concurrent.ExecutionException => throw e.getCause
      case e: Throwable => throw e
    }
  }
  
  override def toString: String = s"WorkStealingFiber(id=$id)"
}

/**
 * Companion object for WorkStealingFiber.
 */
object WorkStealingFiber {
  
  /**
   * Fire and forget task execution.
   */
  def fireAndForget(task: Task[_]): Unit = {
    WorkStealingPool.submit(task)
    ()
  }
  
  /**
   * Check if work-stealing is enabled.
   */
  def isEnabled: Boolean = {
    sys.props.getOrElse("rapid.work-stealing", "true").toBoolean
  }
  
  /**
   * Get statistics about the work-stealing pool.
   */
  def getPoolStats: WorkStealingPool.PoolStats = {
    WorkStealingPool.getStats
  }
}