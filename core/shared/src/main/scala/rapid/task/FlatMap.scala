package rapid.task

import rapid.Task
import rapid.trace.Trace

case class FlatMap[Input, +Return](input: Task[Input], f: Input => Task[Return], trace: Trace) extends Task[Return]
