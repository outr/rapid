package rapid.v2

case class FlatMap[Input, Return](input: Task[Input], f: Input => Task[Return], trace: Trace) extends Task[Return]
