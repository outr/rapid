package rapid.task

import rapid.{Forge, Task}

case class FlatMapTask[Input, Return](source: Task[Input], forge: Forge[Input, Return]) extends Task[Return] {
  lazy val contAny: Any => Task[Any] = 
    (v: Any) => forge(v.asInstanceOf[Input]).asInstanceOf[Task[Any]]
  override def toString: String = "FlatMap"
}
