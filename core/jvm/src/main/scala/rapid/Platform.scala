package rapid

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration
import java.util.concurrent.{ThreadPoolExecutor, TimeUnit, LinkedBlockingQueue, Executors, Executor}
import java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy
import java.util.concurrent.atomic.AtomicBoolean

object Platform extends RapidPlatform {
  override def executionContext: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global

  override def supportsCancel: Boolean = true
  



  override def createFiber[Return](task: Task[Return]): Fiber[Return] = {
    // Always use FixedThreadPoolFiber
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
      TimerWheel.schedule(duration, () => {
        completable.success(())
      })
      completable
    }
  }
}
