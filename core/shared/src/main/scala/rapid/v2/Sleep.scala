package rapid.v2

import scala.concurrent.duration.FiniteDuration

case class Sleep(duration: FiniteDuration, trace: Trace) extends Task[Unit]
