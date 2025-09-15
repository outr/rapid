package rapid.task

import rapid.Task

trait UnitTask extends ImmediateTask[Unit] {
  // Provide the immediate value for ImmediateTask
  override protected def immediateValue: Unit = ()
  
  override def toString: String = "Unit"
}

object UnitTask extends UnitTask