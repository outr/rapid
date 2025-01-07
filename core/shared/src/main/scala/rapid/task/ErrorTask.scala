package rapid.task

import rapid.Task

case class ErrorTask[Return](throwable: Throwable) extends AnyVal with Task[Return] {
  override def flatMap[T](f: Return => Task[T]): Task[T] = this.asInstanceOf[Task[T]]

  override def toString: String = s"Error(${throwable.getMessage})"
}