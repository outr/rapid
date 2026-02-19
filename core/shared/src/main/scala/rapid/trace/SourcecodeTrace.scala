package rapid.trace

import sourcecode.{Enclosing, File, Line}

final case class SourcecodeTrace(file: File, line: Line, enclosing: Enclosing, kind: String) extends Trace {
  override def toSTE: Option[StackTraceElement] = {
    val cls = enclosing.value
    val fileN = {
      val p = file.value
      val i = math.max(p.lastIndexOf('/'), p.lastIndexOf('\\'))
      if (i >= 0) p.substring(i + 1) else p
    }
    Some(new StackTraceElement(cls, s"<$kind>", fileN, line.value))
  }

  override def toString: String = s"<$kind>"
}
