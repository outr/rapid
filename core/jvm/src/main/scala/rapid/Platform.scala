package rapid

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration
import java.util.concurrent.{ThreadPoolExecutor, TimeUnit, LinkedBlockingQueue, Executors, Executor, CompletableFuture}
import java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy
import java.util.concurrent.atomic.AtomicBoolean

object Platform extends RapidPlatform {
  override def executionContext: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global

  override def supportsCancel: Boolean = true
  
  /**
   * Check if cooperative yielding is supported.
   */
  override def supportsCooperativeYielding: Boolean = false
  
  /**
   * Perform a sync operation on a CompletableFuture.
   */
  override def cooperativeSync[T](future: CompletableFuture[T]): T = {
    // Default blocking behavior
    try {
      future.get()
    } catch {
      case ex: java.util.concurrent.ExecutionException =>
        throw ex.getCause
    }
  }
  



  override def createFiber[Return](task: Task[Return]): Fiber[Return] = {
    new FixedThreadPoolFiber[Return](task)
  }

  override def fireAndForget(task: Task[_]): Unit = {
    // Use FixedThreadPoolFiber with our universal TimerWheel for all async operations
    FixedThreadPoolFiber.fireAndForget(task)
  }

  override def sleep(duration: FiniteDuration): Task[Unit] = {
    import scala.concurrent.duration._
    
    val millis = duration.toMillis
    if (millis <= 0L) {
      Task.unit
    } else {
      // Use THE single timer solution for all of Rapid
      val completable = Task.completable[Unit]
      FixedThreadPoolFiber.scheduledExecutor.schedule(new Runnable {
        def run(): Unit = completable.success(())
      }, duration.toMillis, TimeUnit.MILLISECONDS)
      completable
    }
  }
}
