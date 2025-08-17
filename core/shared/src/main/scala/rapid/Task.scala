package rapid

import rapid.monitor.TaskMonitor
import rapid.task.{
  CompletableTask,
  ErrorTask,
  FlatMapTask,
  PureTask,
  SingleTask,
  SleepTask,
  Taskable,
  UnitTask
}

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import scala.collection.BuildFrom
import scala.concurrent.duration.{DurationLong, FiniteDuration}
import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try}

/**
 * Represents a task that can be executed to produce a result of type Return.
 */
trait Task[+Return] extends Any {

  def sync(): Return = {
    val stack = new java.util.ArrayDeque[Any]()
    stack.push(this)

    var previous: Any = ()

    while (!stack.isEmpty) {
      val head = stack.pop()

      if (Task.monitor != null) {
        head match {
          case t: Task[_] => Task.monitor.start(t)
          case _          => // Ignore
        }
      }

      try {
        head match {
          case _: UnitTask             => previous = ()
          case PureTask(thunk)        => previous = thunk()
          case SingleTask(f)          => previous = f()
          case SleepTask(d)           => previous = Platform.sleep(d).sync()
          case t: Taskable[_]         => stack.push(t.toTask)
          case ErrorTask(throwable)   => throw throwable
          case c: CompletableTask[_]  => previous = c.sync()
          case f: Fiber[_]            => previous = f.sync() // FIXED: fiber must call sync()
          case f: Forge[_, _]         => stack.push(f.asInstanceOf[Forge[Any, Any]](previous))
          case FlatMapTask(source, f) =>
            stack.push(f)
            stack.push(source)
          case _ =>
            throw new UnsupportedOperationException(s"Unsupported task: $head (${head.getClass.getName})")
        }

        if (Task.monitor != null) {
          head match {
            case t: Task[_] => Task.monitor.success(t, previous)
            case _          => // Ignore
          }
        }

      } catch {
        case throwable: Throwable =>
          if (Task.monitor != null) {
            head match {
              case t: Task[_] => Task.monitor.error(t, throwable)
              case _          => // Ignore
            }
          }
          throw throwable
      }
    }

    previous.asInstanceOf[Return]
  }

  def apply(): Return = sync()

  def map[T](f: Return => T): Task[T] = this match {
    case PureTask(thunk) => PureTask(() => f(thunk()))
    case SingleTask(g)   => SingleTask(() => f(g()))
    case _               => FlatMapTask(this, Forge[Return, T](i => PureTask(() => f(i))))
  }

  def flatMap[T](f: Return => Task[T]): Task[T] = this match {
    case PureTask(thunk) => f(thunk())
    case _               => FlatMapTask(this, Forge(f))
  }
}

object Task {
  var monitor: TaskMonitor = null

  def apply[A](thunk: => A): Task[A] =
    SingleTask(() => thunk)

  def pure[A](value: A): Task[A] =
    PureTask(() => value)

  def defer[A](thunk: => Task[A]): Task[A] =
    FlatMapTask(PureTask(() => ()), Forge(_ => thunk)) // FIXED

  def error[A](throwable: Throwable): Task[A] =
    ErrorTask(throwable)

  val unit: Task[Unit] =
    PureTask(() => ())

  def completable[A]: CompletableTask[A] =
    new CompletableTask[A]()

  def sequence[A, M[X] <: IterableOnce[X]](in: M[Task[A]])(implicit bf: BuildFrom[M[Task[A]], A, M[A]]): Task[M[A]] = {
    val iterator = in.iterator
    def loop(acc: List[A]): Task[List[A]] =
      if (!iterator.hasNext) Task.pure(acc.reverse)
      else iterator.next().flatMap(a => loop(a :: acc))

    loop(Nil).map(xs => {
      val builder = bf.newBuilder(in)
      builder ++= xs
      builder.result()
    })
  }

  def parSequence[A, M[X] <: IterableOnce[X]](in: M[Task[A]])(implicit bf: BuildFrom[M[Task[A]], A, M[A]]): Task[M[A]] =
    sequence(in) // stub: replace with parallel implementation if needed
}
