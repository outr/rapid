package rapid

import scala.concurrent.duration.FiniteDuration

/**
 * Represents a task that can be executed to produce a result of type `Return`.
 *
 * @tparam Return the type of the result produced by this task
 */
trait Task[Return] extends Any {
  protected def f: () => Return

  /**
   * Synchronously (blocking) executes the task and returns the result.
   *
   * @return the result of the task
   */
  def sync(): Return = f()

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
  def attempt(): Either[Throwable, Return] = start().attempt()

  /**
   * Transforms the result of the task using the given function.
   *
   * @param f the function to transform the result
   * @tparam T the type of the transformed result
   * @return a new task with the transformed result
   */
  def map[T](f: Return => T): Task[T] = Task(f(this.f()))

  /**
   * Flat maps the result of the task using the given function.
   *
   * @param f the function to transform the result into a new task
   * @tparam T the type of the result of the new task
   * @return a new task with the transformed result
   */
  def flatMap[T](f: Return => Task[T]): Task[T] = ChainedTask(List(
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
  /**
   * A task that returns `Unit`.
   */
  lazy val unit: Task[Unit] = apply(())

  /**
   * Creates a new task with the given function.
   *
   * @param f the function to execute
   * @tparam Return the type of the result produced by the task
   * @return a new task
   */
  def apply[Return](f: => Return): Task[Return] = new SimpleTask(() => f)

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
}