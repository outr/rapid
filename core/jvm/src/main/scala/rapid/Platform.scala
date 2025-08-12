package rapid

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

object Platform extends RapidPlatform {
  override def executionContext: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global

  override def supportsCancel: Boolean = true

  override def createFiber[Return](task: Task[Return]): Fiber[Return] = new VirtualThreadFiber[Return](task)

  override def fireAndForget(task: Task[_]): Unit = VirtualThreadFiber.fireAndForget(task)

  override def sleep(duration: FiniteDuration): Task[Unit] = Task.defer {
    val millis = duration.toMillis
    if (millis > 0L) {
      Task(Thread.sleep(millis))
    } else {
      Task.unit
    }
  }
}
