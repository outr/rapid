package rapid

import scala.collection.BuildFrom
import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success, Try}

/**
 * Represents a task that can be executed to produce a result of type `Return`.
 *
 * @tparam Return the type of the result produced by this task
 */
trait Task[Return] extends Any {
  protected def invoke(): Return

  /**
   * Synonym for sync(). Allows for clean usage with near transparent invocations.
   *
   * @return the result of the task
   */
  def apply(): Return = sync()

  /**
   * Synchronously (blocking) executes the task and returns the result.
   *
   * @return the result of the task
   */
  def sync(): Return = invoke()

  /**
   * Starts the task and returns a `Fiber` representing the running task.
   *
   * @return a `Fiber` representing the running task
   */
  def start(): Fiber[Return] = Platform.createFiber(this)

  /**
   * Awaits (blocking) the completion of the task and returns the result.
   *
   * @return the result of the task
   */
  def await(): Return = start().await()

  /**
   * Attempts to execute the task and returns either the result or an exception.
   *
   * @return either the result of the task or an exception
   */
  def attempt: Task[Try[Return]] = Task {
    Try(invoke())
  }

  /**
   * Handles error in task execution.
   *
   * @param f handler
   * @return Task[Return]
   */
  def handleError(f: Throwable => Task[Return]): Task[Return] = attempt
    .flatMap {
      case Success(r) => Task.pure(r)
      case Failure(t) => f(t)
    }

  /**
   * Guarantees execution even if there's an exception at a higher level
   *
   * @param task the task to guarantee invocation of
   */
  def guarantee(task: Task[Unit]): Task[Return] = attempt
    .flatTap { _ =>
      task
    }
    .map(_.get)

  /**
   * Transforms the result of the task using the given function.
   *
   * @param f the function to transform the result
   * @tparam T the type of the transformed result
   * @return a new task with the transformed result
   */
  def map[T](f: Return => T): Task[T] = Task(f(invoke()))

  /**
   * Flat maps the result of the task using the given function.
   *
   * @param f the function to transform the result into a new task
   * @tparam T the type of the result of the new task
   * @return a new task with the transformed result
   */
  def flatMap[T](f: Return => Task[T]): Task[T] = Task.Chained(List(
    v => f(v.asInstanceOf[Return]).asInstanceOf[Task[Any]],
    (_: Any) => this.asInstanceOf[Task[Any]],
  ))

  /**
   * Similar to flatMap, but ignores the return propagating the current return value on.
   *
   * @param f the function to handle the result
   * @return existing signature
   */
  def flatTap(f: Return => Task[_]): Task[Return] = flatMap { r =>
    f(r).map(_ => r)
  }

  /**
   * Chains a sleep to the end of this task.
   *
   * @param duration the duration to sleep
   * @return a new task that sleeps for the given duration after the existing task completes
   */
  def sleep(duration: FiniteDuration): Task[Return] = flatMap { r =>
    Task.sleep(duration).map(_ => r)
  }

  /**
   * Convenience conditional execution of the Task. If the condition is true, the task will execute the instruction set,
   * but if false, it will immediately return upon execution doing nothing.
   */
  def when(condition: Boolean): Task[Unit] = if (condition) {
    this.unit
  } else {
    Task.unit
  }

  /**
   * Chains the task to a Unit result.
   *
   * @return a new task that returns `Unit` after the existing task completes
   */
  def unit: Task[Unit] = map(_ => ())

  /**
   * Provides convenience functionality to execute this Task as a scala.concurrent.Future.
   */
  def toFuture(implicit ec: scala.concurrent.ExecutionContext): scala.concurrent.Future[Return] =
    scala.concurrent.Future(this.sync())
}

object Task {
  case class Pure[Return](value: Return) extends AnyVal with Task[Return] {
    override protected def invoke(): Return = value
  }

  case class Single[Return](f: () => Return) extends AnyVal with Task[Return] {
    override protected def invoke(): Return = f()
  }

  case class Chained[Return](list: List[Any => Task[Any]]) extends AnyVal with Task[Return] {
    override protected def invoke(): Return = list.reverse.foldLeft((): Any)((value, f) => f(value).sync()).asInstanceOf[Return]

    override def flatMap[T](f: Return => Task[T]): Task[T] = copy(f.asInstanceOf[Any => Task[Any]] :: list)
  }

  case class Error[Return](throwable: Throwable) extends AnyVal with Task[Return] {
    override protected def invoke(): Return = throw throwable

    override def flatMap[T](f: Return => Task[T]): Task[T] = this.asInstanceOf[Task[T]]
  }

  class Completable[Return] extends Task[Return] {
    @volatile private var result: Option[Try[Return]] = None

    def success(result: Return): Unit = synchronized {
      this.result = Some(Success(result))
      notifyAll()
    }

    def failure(throwable: Throwable): Unit = synchronized {
      this.result = Some(Failure(throwable))
      notifyAll()
    }

    override protected def invoke(): Return = synchronized {
      while (result.isEmpty) {
        wait()
      }
      result.get.get
    }
  }

  /**
   * A task that returns `Unit`.
   */
  lazy val unit: Task[Unit] = pure(())

  /**
   * Creates a new task with the given value pre-evaluated.
   *
   * @param value the return value of this Task
   * @tparam Return the type of the result produced by the task
   * @return a new task
   */
  def pure[Return](value: Return): Task[Return] = Pure(value)

  /**
   * Creates a new task with the given function.
   *
   * @param f the function to execute
   * @tparam Return the type of the result produced by the task
   * @return a new task
   */
  def apply[Return](f: => Return): Task[Return] = Single(() => f)

  /**
   * Creates a new task that raises an error when invoked.
   *
   * @param throwable the exception to raise
   * @return a new Error task
   */
  def error[Return](throwable: Throwable): Task[Return] = Error[Return](throwable)

  /**
   * Creates a new Completable task.
   *
   * @tparam Return the type of the result produced by the task
   * @return a new Completable task
   */
  def completable[Return]: Completable[Return] = new Completable

  /**
   * Creates a new task that sleeps for the given duration.
   *
   * @param duration the duration to sleep
   * @return a new task that sleeps for the given duration
   */
  def sleep(duration: FiniteDuration): Task[Unit] = apply(Thread.sleep(duration.toMillis))

  /**
   * Effect to get the current time in milliseconds
   */
  def now: Task[Long] = apply(System.currentTimeMillis())

  /**
   * Defers the execution of the given task.
   *
   * @param task the task to defer
   * @tparam Return the type of the result produced by the task
   * @return a new task that defers the execution of the given task
   */
  def defer[Return](task: => Task[Return]): Task[Return] = Task(task.sync())

  /**
   * Converts a sequence of Task[Return] to a Task that returns a sequence of Return. Generally cleaner usage via the
   * implicit in rapid on seq.tasks.
   */
  def sequence[Return, C[_]](tasks: C[Task[Return]])
                            (implicit bf: BuildFrom[C[Task[Return]], Return, C[Return]],
                                      asIterable: C[Task[Return]] => Iterable[Task[Return]]): Task[C[Return]] = {
    val empty = bf.newBuilder(tasks)
    Task {
      asIterable(tasks).foldLeft(empty) {
        case (builder, task) => builder.addOne(task.sync())
      }.result()
    }
  }
}