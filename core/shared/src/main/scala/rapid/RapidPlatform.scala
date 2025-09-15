package rapid

import rapid.task.CompletableTask
import java.util.concurrent.CompletableFuture

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

trait RapidPlatform {
  def executionContext: ExecutionContext

  def supportsCancel: Boolean

  def createFiber[Return](task: Task[Return]): Fiber[Return]

  def fireAndForget(task: Task[_]): Unit

  def sleep(duration: FiniteDuration): Task[Unit]
  
  /**
   * Check if cooperative yielding is supported on this platform.
   * Returns true if we're in a work-stealing worker thread.
   */
  def supportsCooperativeYielding: Boolean = false
  
  /**
   * Perform a cooperative sync operation on a CompletableFuture.
   * Default implementation just blocks.
   */
  def cooperativeSync[T](future: CompletableFuture[T]): T = {
    try {
      future.get()
    } catch {
      case ex: java.util.concurrent.ExecutionException =>
        throw ex.getCause
    }
  }
}
