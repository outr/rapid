package rapid.task

import rapid.Task

case class PureTask[Return](value: Return) extends AnyVal with Task[Return] {
  // Override sync() to avoid ArrayDeque allocation - fast path!
  override def sync(): Return = value
  
  override def toString: String = s"Pure($value)"
}