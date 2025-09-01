package rapid.task

import rapid.Task

trait UnitTask extends Task[Unit] {
  // Override sync() to avoid ArrayDeque allocation - fast path!
  override def sync(): Unit = ()
  
  override def toString: String = "Unit"
}

object UnitTask extends UnitTask