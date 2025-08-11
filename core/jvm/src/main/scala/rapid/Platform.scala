package rapid

import rapid.task.CompletableTask

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

object Platform extends RapidPlatform {
  override def executionContext: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global

  override def supportsCancel: Boolean = true

  override def createFiber[Return](task: Task[Return]): Fiber[Return] = new FixedThreadPoolFiber[Return](task)

  override def fireAndForget(task: Task[_]): Unit = FixedThreadPoolFiber.fireAndForget(task)

  override def sleep(duration: FiniteDuration): CompletableTask[Unit] = {
    val completable = Task.completable[Unit]
    VirtualThreadFiber.fireAndForget(Task {
      Thread.sleep(duration.toMillis)
      completable.success(())
    })
    completable
  }
}
