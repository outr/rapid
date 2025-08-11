package rapid.task

import rapid.Task

import scala.concurrent.duration.FiniteDuration

case class SleepTask(duration: FiniteDuration) extends AnyVal with Task[Unit] {
  override def toString: String = s"Sleep($duration)"
}