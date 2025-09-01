package rapid.task

import rapid.Task

/**
 * Zero-allocation flatMap task that stores the raw function directly.
 * Avoids Forge wrapper allocation for better performance in tight loops.
 */
final case class DirectFlatMapTask[Input, Return](
  source: Task[Input], 
  f: Input => Task[Return]
) extends Task[Return] {
  override def toString: String = "DirectFlatMap"
}