package rapid.task

import rapid.Task

case class SingleTask[Return](f: () => Return) extends AnyVal with Task[Return] {
  override def toString: String = "Single"
}