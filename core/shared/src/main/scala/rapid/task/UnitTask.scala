package rapid.task

import rapid.Task

trait UnitTask extends Task[Unit] {
  /**
   * Override sync() to avoid ArrayDeque allocation - fast path!
   * Since UnitTask completes immediately, we can return Unit directly.
   *
   * SAFETY: It is safe to bypass the trampoline here because UnitTask
   * performs no computation and has no side effects. The task completes
   * immediately without risk of stack overflow.
   */
  override def sync(): Unit = ()
  
  override def toString: String = "Unit"
}

object UnitTask extends UnitTask