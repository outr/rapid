package rapid

import java.util.concurrent.{CompletableFuture, TimeUnit}
import scala.concurrent.duration.FiniteDuration

/**
 * Optimized Fiber for sleep + callback pattern.
 * Bypasses Task creation entirely to reduce object allocations.
 */
class SleepCallbackFiber[T](duration: FiniteDuration, callback: () => T) extends AbstractFiber[T] {
  
  // Assign unique ID on creation
  override val id: Long = FiberIdGenerator.nextId()
  
  private val future = new CompletableFuture[T]()
  
  // Schedule the callback directly without creating Task objects
  private val scheduledFuture = FixedThreadPoolFiber.scheduledExecutor.schedule(new Runnable {
    def run(): Unit = {
      try {
        val result = callback()
        future.complete(result)
      } catch {
        case e: Throwable => future.completeExceptionally(e)
      }
    }
  }, duration.toMillis, TimeUnit.MILLISECONDS)
  
  override protected def doSync(): T = future.get()
  
  override protected def performCancellation(): Unit = {
    scheduledFuture.cancel(false)
    future.cancel(true)
    ()
  }
  
  override protected def doAwait(timeout: Long, unit: TimeUnit): Option[T] = {
    try {
      Some(future.get(timeout, unit))
    } catch {
      case _: java.util.concurrent.TimeoutException => None
    }
  }
  
  override def toString: String = s"SleepCallbackFiber($duration)"
}