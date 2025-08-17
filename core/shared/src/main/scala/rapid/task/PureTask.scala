package rapid.task

import rapid.Task

final case class PureTask[A](value: () => A) extends Task[A]
