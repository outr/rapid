package rapid.task

import scala.concurrent.duration._

sealed trait Task[+A] {
  def run(callback: TaskCallback[A]): Unit
}

object Task {
  case class Sleep(duration: FiniteDuration, cont: () => Task[Unit]) extends Task[Unit] {
    override def run(callback: TaskCallback[Unit]): Unit = callback(this)
  }

  case object Complete extends Task[Unit] {
    override def run(callback: TaskCallback[Unit]): Unit = callback(this)
  }

  def sleep(duration: FiniteDuration): Task[Unit] =
    Sleep(duration, () => Complete)
}

trait TaskCallback[A] {
  def apply(value: Task[A]): Unit
}
