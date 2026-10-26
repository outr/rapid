package rapid.trace

import sourcecode._

trait Trace {
  def toSTE: Option[StackTraceElement]
}

object Trace {
  var Enabled: Boolean = true

  /**
   * How many pending task frames one fiber remembers, at most.
   *
   * The interpreter records a frame by leaving its trace on the work stack, where it is removed once
   * that frame's work is done. A task that loops - `work.flatMap(_ => loop)` - enters the next frame
   * without ever finishing the last, so with no limit every iteration leaves one behind for the life
   * of the fiber: a long-lived loop becomes a heap leak of 80 bytes an iteration. Measured in a server
   * on 2026-10-26, after seven days up: 220,709,707 of them, 17.6GB of a 24GB heap, growing by 946 a
   * second.
   *
   * Past this many frames the interpreter stops recording new ones, so a trace keeps the innermost
   * frames and loses the outermost. A loop loses nothing, since its frames are all the same frame and
   * collapse into one in the trace either way; a deep finite chain loses whatever is beyond the
   * sixty-fourth frame, which nobody reads.
   */
  var MaxFrames: Int = 64

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