package rapid.task

import rapid.{Forge, Task}

case class FlatMapTask[Input, Return](source: Task[Input], forge: Forge[Input, Return]) extends Task[Return] {
  override def toString: String = "FlatMap"
}