package rapid

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration
import java.util.concurrent.TimeUnit

object Platform extends RapidPlatform {
  override def executionContext: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global

  override def supportsCancel: Boolean = true

  override def createFiber[Return](task: Task[Return]): Fiber[Return] = {
    new FixedThreadPoolFiber[Return](task)
  }

  override def fireAndForget(task: Task[_]): Unit = {
    // Use FixedThreadPoolFiber with our universal TimerWheel for all async operations
    FixedThreadPoolFiber.fireAndForget(task)
  }

  override def sleep(duration: FiniteDuration): Task[Unit] = {
    import scala.concurrent.duration._

    val nanos = duration.toNanos
    if (nanos <= 0L) {
      Task.unit
    } else {
      // Use THE single timer solution for all of Rapid
      // Use nanoseconds for better precision to avoid early wake-ups
      val completable = Task.completable[Unit]
      FixedThreadPoolFiber.scheduledExecutor.schedule(new Runnable {
        def run(): Unit = completable.success(())
      }, nanos, TimeUnit.NANOSECONDS)
      completable
    }
  }
}
