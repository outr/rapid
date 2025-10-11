package rapid.v2

import java.util
import scala.annotation.tailrec

case class SynchronousFiber[Return](task: Task[Return]) extends Fiber[Return] {
  private val TraceBufSize = 256
  private val traceBuf = new Array[Trace](TraceBufSize)
  private var traceIdx = 0

  @inline private def record(tr: Trace): Unit = if (tr != Trace.empty) {
    traceBuf(traceIdx & (TraceBufSize - 1)) = tr
    traceIdx += 1
  }

  // cache once so the JIT can dead-code-eliminate trace paths
  private val tracing = Trace.Enabled

  private var lastTrace: Trace = Trace.empty
  private val stack = new util.ArrayDeque[Any]
  private var previous: Any = ()

  stack.push(task)

  @tailrec
  final def execute(): Unit =
    if (stack.isEmpty) {
      // Finished
    } else {
      try {
        stack.pop() match {
          case task: Task[_] =>
            task match {
              case _: UnitTask => // Ignore
              case Pure(value) =>
                previous = value
              case Suspend(f, tr) =>
                if (tracing) {
                  lastTrace = tr
                  record(tr)
                }
                previous = f()
              case Sleep(duration, tr) =>
                if (tracing) {
                  lastTrace = tr
                  record(tr)
                }
                Thread.sleep(duration.toMillis)
              case c@Completable(tr) =>
                if (tracing) {
                  lastTrace = tr
                  record(tr)
                }
                previous = c.sync()
              case FlatMap(input, f, tr) =>
                // If tracing is OFF, avoid allocating a tuple
                if (tracing) stack.push((f, tr)) else stack.push(f)
                stack.push(input)
            }
          case f: Function1[_, _] =>
            // fast path when tracing is OFF (we pushed 'f' directly)
            val next = f.asInstanceOf[Any => Task[Any]](previous)
            stack.push(next)
          case (f: Function1[_, _], tr: Trace) =>
            if (tracing) {
              lastTrace = tr
              record(tr)
            }
            val next = f.asInstanceOf[Any => Task[Any]](previous)
            stack.push(next)
        }
      } catch {
        case t: Throwable =>
          if (tracing) {
            // Build frames from executed history only (most-recent first)
            val n = math.min(traceIdx, TraceBufSize)
            val frames =
              List.tabulate(n)(i => traceBuf((traceIdx - 1 - i) & (TraceBufSize - 1)))
                .filter(_ != Trace.empty)
                .distinct
            throw Trace.update(t, frames)
          } else {
            throw t
          }
      }
      execute()
    }

  execute()

  override def sync(): Return = previous.asInstanceOf[Return]
}
