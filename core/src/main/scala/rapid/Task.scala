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
  def start(): Fiber[Return] = new Fiber(this)

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
  def attempt(): Try[Return] = start().attempt()

  /**
   * Handles error in task execution.
   *
   * @param f handler
   * @return Task[Return]
   */
  def handleError(f: Throwable => Task[Return]): Task[Return] = Task(attempt())
    .flatMap {
      case Success(r) => Task.pure(r)
      case Failure(t) => f(t)
    }

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
   * Chains a sleep to the end of this task.
   *
   * @param duration the duration to sleep
   * @return a new task that sleeps for the given duration after the existing task completes
   */
  def sleep(duration: FiniteDuration): Task[Return] = flatMap { r =>
    Task.sleep(duration).map(_ => r)
  }

  /**
   * Chains the task to a Unit result.
   *
   * @return a new task that returns `Unit` after the existing task completes
   */
  def unit: Task[Unit] = map(_ => ())

  /**
   * Converts the task to a `Pull`.
   *
   * @return a `Pull` representing the task
   */
  def toPull: Pull[Return] = Pull.suspend(Pull.pure(sync()))
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

  class Completable[Return] extends Task[Return] {
    @volatile private var result: Option[Return] = None

    def complete(result: Return): Unit = synchronized {
      this.result = Some(result)
      notifyAll()
    }

    override protected def invoke(): Return = synchronized {
      while (result.isEmpty) {
        wait()
      }
      result.get
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