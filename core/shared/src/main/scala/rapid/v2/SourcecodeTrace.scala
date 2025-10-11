package rapid.v2

import sourcecode.{Enclosing, File, Line}

final case class SourcecodeTrace(file: File, line: Line, enclosing: Enclosing) extends Trace {
  override def toSTE: Option[StackTraceElement] = {
    val cls = enclosing.value
    val fileN = {
      val p = file.value
      val i = p.lastIndexOf(java.io.File.separatorChar)
      if (i >= 0) p.substring(i + 1) else p
    }
    Some(new StackTraceElement(cls, "<task>", fileN, line.value))
  }

  override def toString: String = "<trace>"
}
