package rapid.v2

import sourcecode.{Enclosing, File, Line}

import scala.concurrent.duration.FiniteDuration

trait Task[Return] {
  protected def trace: Trace

  protected def exec(mode: ExecutionMode): Fiber[Return] = mode match {
    case ExecutionMode.Synchronous => SynchronousFiber(this)
    case ExecutionMode.Asynchronous => FixedThreadPoolFiber(this)
  }

  def pure[T](value: T): Task[T] =
    FlatMap(this, (_: Return) => Pure(value), Trace.empty)

  def apply[T](f: => T)(implicit file: File, line: Line, enclosing: Enclosing): Task[T] = map(_ => f)

  def map[T](f: Return => T)(implicit file: File, line: Line, enclosing: Enclosing): Task[T] = {
    val tr = if (Trace.Enabled) Trace(file, line, enclosing) else Trace.empty
    this match {
      case Pure(r) => Suspend(() => f(r), tr)
      case Suspend(pf, _) => Suspend(() => f(pf()), tr)
      case _ => flatMap(r => Suspend(() => f(r), tr))
    }
  }

  def flatMap[T](f: Return => Task[T])(implicit file: File, line: Line, enclosing: Enclosing): Task[T] = {
    val tr = if (Trace.Enabled) Trace(file, line, enclosing) else Trace.empty
    FlatMap(this, f, tr)
  }

  def next[T](task: => Task[T])(implicit file: File, line: Line, enclosing: Enclosing): Task[T] =
    flatMap(_ => task)

  def flatTap(f: Return => Task[_])(implicit file: File, line: Line, enclosing: Enclosing): Task[Return] = flatMap { r =>
    f(r).map(_ => r)
  }

  def effect[T](task: => Task[T])(implicit file: File, line: Line, enclosing: Enclosing): Task[Return] = flatTap(_ => task)

  def sleep(duration: FiniteDuration)(implicit file: File, line: Line, enclosing: Enclosing): Task[Return] = {
    val tr = if (Trace.Enabled) Trace(file, line, enclosing) else Trace.empty
    effect(Sleep(duration, tr))
  }

  final def sync(): Return = exec(ExecutionMode.Synchronous).sync()

  final def apply(): Return = sync()

  final def start: Task[Fiber[Return]] = Suspend(() => exec(ExecutionMode.Asynchronous), Trace.empty)
}

object Task extends UnitTask {
  override def pure[T](value: T): Task[T] = Pure(value)

  override def apply[T](f: => T)(implicit file: File, line: Line, enclosing: Enclosing): Task[T] =
    Suspend(() => f, Trace(file, line, enclosing))
}