package rapid.task

import rapid.Task

/**
 * Trait for tasks that return immediately without needing to execute asynchronous operations.
 * This provides a fast path optimization by avoiding ArrayDeque allocation in sync().
 * 
 * Used by PureTask (returns a value) and UnitTask (returns Unit).
 */
trait ImmediateTask[Return] extends Task[Return] {
  /**
   * The immediate value to return.
   * Subclasses must provide this value.
   */
  protected def immediateValue: Return
  
  /**
   * Override sync() to avoid ArrayDeque allocation - fast path!
   * Since the task completes immediately, we can return the value directly.
   */
  override def sync(): Return = immediateValue
}