package rapid.fiber

import rapid.{Fiber, Task}
import rapid.task.Pure

import scala.util.{Success, Try}

final case class CompletedFiber[+Return](value: Return) extends Fiber[Return] {
  override def sync(): Return = value
  override def join: Task[Return] = Pure(value)
  override def onComplete(f: Try[Return] => Unit): Unit = f(Success(value))
}
