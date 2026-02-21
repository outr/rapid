package rapid.trace

import sourcecode._

trait Trace {
  def toSTE: Option[StackTraceElement]
}

object Trace {
  var Enabled: Boolean = true

  object empty extends Trace {
    override def toSTE: Option[StackTraceElement] = None
  }

  def apply(file: File, line: Line, enclosing: Enclosing, kind: String): Trace = if (Enabled) {
    SourcecodeTrace(file, line, enclosing, kind)
  } else {
    empty
  }

  private def isInterpreterFrame(ste: StackTraceElement): Boolean = {
    val cls = ste.getClassName
    cls.startsWith("rapid.fiber.SynchronousFiber") ||
      cls.startsWith("rapid.fiber.FixedThreadPoolFiber")
  }

  def update(throwable: Throwable, traceList: List[Trace]): Throwable = {
    if (Enabled) {
      val original = throwable.getStackTrace
      val tasks = traceList.flatMap(_.toSTE).toArray

      if (original.nonEmpty && tasks.nonEmpty) {
        val first = original(0)
        val rest = original.drop(1).filterNot(isInterpreterFrame)
        val ste = new Array[StackTraceElement](1 + tasks.length + rest.length)
        ste(0) = first
        System.arraycopy(tasks, 0, ste, 1, tasks.length)
        System.arraycopy(rest, 0, ste, 1 + tasks.length, rest.length)
        throwable.setStackTrace(ste)
      }
    }
    throwable
  }
}