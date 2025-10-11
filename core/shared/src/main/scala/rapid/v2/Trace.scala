package rapid.v2

import sourcecode._

trait Trace {
  def toSTE: Option[StackTraceElement]
}

object Trace {
  @volatile var Enabled: Boolean = false

  object empty extends Trace {
    override def toSTE: Option[StackTraceElement] = None
  }

  def apply(file: File, line: Line, enclosing: Enclosing): Trace = if (Enabled) {
    SourcecodeTrace(file, line, enclosing)
  } else {
    empty
  }

  def update(throwable: Throwable, traceList: List[Trace]): Throwable = {
    if (Enabled) {
      val original = throwable.getStackTrace
      val tasks = traceList.flatMap(_.toSTE).toArray

      val first = original(0)
      val ste = new Array[StackTraceElement](original.length + tasks.length)
      ste(0) = first
      System.arraycopy(tasks, 0, ste, 1, tasks.length)
      System.arraycopy(original, 1, ste, tasks.length + 1, original.length - 1)

      //      val ste = traceList.flatMap(_.toSTE).toArray ++ throwable.getStackTrace
      throwable.setStackTrace(ste)
    }
    throwable
  }
}