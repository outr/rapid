package rapid.task

import rapid.Task
import rapid.trace.Trace

case class HandleError[+Return](task: Task[Return], handler: Throwable => Task[Return], trace: Trace) extends Task[Return]
