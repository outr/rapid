package rapid.task

import rapid.Task

case class PureTask[Return](value: Return) extends AnyVal with Task[Return] {
  override def toString: String = s"Pure($value)"
}