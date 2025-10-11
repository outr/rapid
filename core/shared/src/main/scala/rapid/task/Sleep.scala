package rapid.task

import rapid.Task
import rapid.trace.Trace

import scala.concurrent.duration.FiniteDuration

case class Sleep(duration: FiniteDuration, trace: Trace) extends Task[Unit]
