package rapid.task

import rapid.Task
import rapid.trace.Trace

/**
 * Taskable provides a convenient trait to mix-in to a custom class to allow it to both be a Task from the outside and
 * easily create the task from the inside.
 *
 * @tparam Return the type of the result produced by this task
 */
trait Taskable[Return] extends Task[Return] {
  override protected def trace: Trace = Trace.empty
  /**
   * Creates the actual Task to execute
   */
  def toTask: Task[Return]
}
