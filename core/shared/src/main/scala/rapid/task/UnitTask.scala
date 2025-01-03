package rapid.task

import rapid.Task

trait UnitTask extends Task[Unit] {
  override def toString: String = "Unit"
}

object UnitTask extends UnitTask