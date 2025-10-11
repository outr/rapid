package rapid.v2

case class Pure[Return](r: Return) extends Task[Return] {
  override protected def trace: Trace = Trace.empty
}
