package rapid

import sourcecode.{Enclosing, File, Line}

import java.util
import java.util.concurrent.{Executors, ScheduledExecutorService, ThreadFactory}
import scala.annotation.tailrec
import scala.concurrent.duration.FiniteDuration
import scala.util.Try

// Run benchmarks!
// Interrupt / Cancel support
object Test3 {
  def main(args: Array[String]): Unit = {
    /*val task = Task
      .pure("Testing")
      .map(_.length)
      .map(_ * 30)
      .map[Int](_ => throw new RuntimeException("Failure!"))
      .map(_ * 100)
    println("Run...")
    val fiber = task.start()
    val result = fiber.sync()
    println(s"Result: $result")*/

    val start = System.currentTimeMillis()

    def add(i: Int): Task[Int] = if (i == 100_000_000) {
      Task.pure(i)
//    } else if (i == 900_000) {
//      throw new RuntimeException("FAIL!")
    } else {
      Task.pure(i + 1).flatMap(add)
    }

    val counter = Task.pure(0).flatMap(add)
    val fiber = counter.start.sync()
    val value = fiber.sync()
    println(s"Value: $value, Time: ${System.currentTimeMillis() - start}")

    def addOriginal(i: Int): rapid.Task[Int] = if (i == 100_000_000) {
      rapid.Task.pure(i)
    } else {
      rapid.Task.pure(i + 1).flatMap(addOriginal)
    }

    val start2 = System.currentTimeMillis()
    val counter2 = rapid.Task.pure(0).flatMap(addOriginal)
    val fiber2 = counter2.start.sync()
    val value2 = fiber2.sync()
    println(s"Value2: $value2, Time: ${System.currentTimeMillis() - start2}")
  }

  sealed trait ExecutionMode

  object ExecutionMode {
    case object Synchronous extends ExecutionMode
    case object Asynchronous extends ExecutionMode
  }

  trait Fiber[Return] extends Any {
    def sync(): Return
  }

  case class VirtualThreadFiber[Return](task: Task[Return]) extends Fiber[Return] {
    @volatile private var result: Option[Try[Return]] = None

    private val thread = Thread
      .ofVirtual()
      .start(() => {
        result = Some(Try(SynchronousFiber(task).sync()))
      })

    override def sync(): Return = {
      thread.join()
      result.get.get
    }
  }

  case class FixedThreadPoolFiber[Return](task: Task[Return]) extends Fiber[Return] {
    private val future = FixedThreadPoolFiber.executor.submit(() => {
      Try(SynchronousFiber(task).sync())
    })

    override def sync(): Return = future.get().get
  }

  object FixedThreadPoolFiber {
    private lazy val threadFactory = new ThreadFactory {
      override def newThread(r: Runnable): Thread = {
        val thread = new Thread(r)
        thread.setDaemon(true)
        thread
      }
    }

    private lazy val executor = Executors.newFixedThreadPool(
      math.max(Runtime.getRuntime.availableProcessors() * 2, 4),
      threadFactory
    )

    private lazy val scheduledExecutor: ScheduledExecutorService = Executors.newScheduledThreadPool(
      math.max(Runtime.getRuntime.availableProcessors() / 2, 2),
      new ThreadFactory {
        override def newThread(r: Runnable): Thread = {
          val thread = new Thread(r)
          thread.setDaemon(true)
          thread
        }
      }
    )
  }

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
                case Chain(tasks) => tasks.foreach(f => stack.push(f))
                case Pure(value) => if (value != ()) {
                  previous = value
                }
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
                case c @ Completable(tr) =>
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

  trait Task[Return] {
    protected def trace: Trace

    protected def exec(mode: ExecutionMode): Fiber[Return] = mode match {
      case ExecutionMode.Synchronous => SynchronousFiber(this)
      case ExecutionMode.Asynchronous => VirtualThreadFiber(this)
//      case ExecutionMode.Asynchronous => FixedThreadPoolFiber(this)
    }

    protected def chain[T](next: Return => Task[T]): Task[T] = this match {
      case Chain(list) => Chain(next.asInstanceOf[Any => Task[_]] :: list)
      case _ => Chain(List(_ => this, next.asInstanceOf[Any => Task[_]]))
    }

    def pure[T](value: T): Task[T] = chain(_ => Pure(value))

    def apply[T](f: => T)(implicit file: File, line: Line, enclosing: Enclosing): Task[T] = map(_ => f)

    def map[T](f: Return => T)(implicit file: File, line: Line, enclosing: Enclosing): Task[T] = chain { r =>
      Suspend(() => f(r), Trace(file, line, enclosing))
    }

    def flatMap[T](f: Return => Task[T])(implicit file: File, line: Line, enclosing: Enclosing): Task[T] = chain(f)

    def next[T](task: => Task[T])(implicit file: File, line: Line, enclosing: Enclosing): Task[T] =
      flatMap(_ => task)

    def flatTap(f: Return => Task[_])(implicit file: File, line: Line, enclosing: Enclosing): Task[Return] = flatMap { r =>
      f(r).map(_ => r)
    }

    def effect[T](task: => Task[T])(implicit file: File, line: Line, enclosing: Enclosing): Task[Return] = flatTap(_ => task)

    def sleep(duration: FiniteDuration)(implicit file: File, line: Line, enclosing: Enclosing): Task[Return] =
      effect(Sleep(duration, Trace(file, line, enclosing)))

    final def sync(): Return = exec(ExecutionMode.Synchronous).sync()

    final def apply(): Return = sync()

    final def start: Task[Fiber[Return]] = Task(exec(ExecutionMode.Asynchronous))
  }

  object Task extends Pure[Unit](())

  object UnitTask extends Pure(())

  case class Pure[Return](r: Return) extends Task[Return] {
    override protected def trace: Trace = Trace.empty
  }

  case class Suspend[Return](f: () => Return, trace: Trace) extends Task[Return]

  case class FlatMap[Input, Return](input: Task[Input], f: Input => Task[Return], trace: Trace) extends Task[Return]

  case class Sleep(duration: FiniteDuration, trace: Trace) extends Task[Unit]

  case class Completable[Return](trace: Trace) extends Task[Return] {
    private var _result: Option[Try[Return]] = None

    def result: Option[Try[Return]] = _result

    def complete(result: Try[Return]): Unit = synchronized {
      _result = Some(result)
    }
  }

  case class Chain[Return](list: List[Any => Task[_]]) extends Task[Return] {
    override protected def trace: Trace = Trace.empty
  }

  sealed trait Trace {
    def toSTE: Option[StackTraceElement]
  }

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

  object Trace {
    @volatile var Enabled: Boolean = true

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
}