package rapid.task

import rapid.Task
import scala.concurrent.duration.FiniteDuration

final case class SleepTask(duration: FiniteDuration) extends Task[Unit]
