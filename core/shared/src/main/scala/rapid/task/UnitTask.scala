package rapid.task

import rapid.Task

trait UnitTask extends Task[Unit] {
  /**
   * Override sync() to avoid ArrayDeque allocation - fast path!
   * Since UnitTask completes immediately, we can return Unit directly.
   */
  override def sync(): Unit = ()
  
  override def toString: String = "Unit"
}

object UnitTask extends UnitTask