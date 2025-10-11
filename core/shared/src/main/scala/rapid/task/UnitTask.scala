package rapid.task

import rapid.Task
import rapid.trace.Trace

trait UnitTask extends Task[Unit] {
  override protected def trace: Trace = Trace.empty

  override def toString: String = "Unit"
}

object UnitTask extends UnitTask
