package rapid.v2

case class Suspend[Return](f: () => Return, trace: Trace) extends Task[Return]
