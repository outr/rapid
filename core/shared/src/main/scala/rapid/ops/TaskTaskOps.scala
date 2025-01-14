package rapid.ops

import rapid.Task

case class TaskTaskOps[Return](task: Task[Task[Return]]) extends AnyVal {
  def flatten: Task[Return] = task.flatMap(identity)
}
