package rapid.v2

import java.util.concurrent.{Executors, ScheduledExecutorService, ThreadFactory}
import scala.util.Try

case class FixedThreadPoolFiber[Return](task: Task[Return]) extends Fiber[Return] {
  private val future = FixedThreadPoolFiber.executor.submit(() => {
    Try(SynchronousFiber(task).sync())
  })

  override def sync(): Return = future.get().get
}

object FixedThreadPoolFiber {
  private lazy val threadFactory = new ThreadFactory {
    override def newThread(r: Runnable): Thread = {
      val thread = new Thread(r)
      thread.setDaemon(true)
      thread
    }
  }

  private lazy val executor = Executors.newFixedThreadPool(
    math.max(Runtime.getRuntime.availableProcessors() * 2, 4),
    threadFactory
  )

  private lazy val scheduledExecutor: ScheduledExecutorService = Executors.newScheduledThreadPool(
    math.max(Runtime.getRuntime.availableProcessors() / 2, 2),
    new ThreadFactory {
      override def newThread(r: Runnable): Thread = {
        val thread = new Thread(r)
        thread.setDaemon(true)
        thread
      }
    }
  )
}