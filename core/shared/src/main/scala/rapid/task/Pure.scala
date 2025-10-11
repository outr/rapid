package rapid.task

import rapid.Task
import rapid.trace.Trace

case class Pure[Return](r: Return) extends Task[Return] {
  override protected def trace: Trace = Trace.empty
}
