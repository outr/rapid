package rapid.task

import rapid.Task
import rapid.trace.Trace

case class Suspend[+Return](f: () => Return, trace: Trace) extends Task[Return]
