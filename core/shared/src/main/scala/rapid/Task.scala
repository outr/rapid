package rapid

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import scala.collection.BuildFrom
import scala.concurrent.duration.FiniteDuration
import scala.reflect.ClassTag
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
   * Transforms this task to a pure result.
   *
   * @param value the return value of this Task
   * @tparam T the type of the result produced by the task
   * @return a new task
   */
  def pure[T](value: T): Task[T] = flatMap(_ => Task.Pure(value))

  /**
   * Transforms the result of this task to the result of the supplied function.
   *
   * @param f the function to execute
   * @tparam T the type of the result produced by the task
   * @return a new task
   */
  def apply[T](f: => T): Task[T] = Task.Single(() => f)

  /**
   * Similar to map, but does not change the value.
   *
   * @param f the function to apply to underlying value
   * @return Task[Return]
   */
  def foreach(f: Return => Unit): Task[Return] = map { r =>
    f(r)
    r
  }

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
  def sleep(duration: FiniteDuration): Task[Return] = flatTap { r =>
    Task(Thread.sleep(duration.toMillis))
  }

  /**
   * Effect to get the current time in milliseconds
   */
  def now: Task[Long] = flatMap(_ => Task(System.currentTimeMillis()))

  /**
   * Defers the execution of the given task.
   *
   * @param task the task to defer
   * @tparam T the type of the result produced by the task
   * @return a new task that defers the execution of the given task
   */
  def defer[T](task: => Task[T]): Task[T] = flatMap(_ => task)

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
   * Combines the two tasks to execute at the same time
   *
   * @param that the second task to execute
   */
  def and[T](that: Task[T]): Task[(Return, T)] = {
    val f1 = this.start()
    val f2 = that.start()
    f1.flatMap(r => f2.map(t => r -> t))
  }

  /**
   * Makes this Task execute exactly once. Any future calls to this Task will return the result of the first execution.
   */
  def singleton: Task[Return] = {
    val triggered = new AtomicBoolean(false)
    val completable = Task.completable[Return]
    val actualTask = map { r =>
      completable.success(r)
    }

    Task {
      val active = triggered.compareAndSet(false, true)
      if (active) {
        actualTask.start().flatMap(_ => completable)
      } else {
        completable
      }
    }.flatten
  }

  /**
   * Converts a sequence of Task[Return] to a Task that returns a sequence of Return. Generally cleaner usage via the
   * implicit in rapid on seq.tasks.
   */
  def sequence[T, C[_]](tasks: C[Task[T]])
                       (implicit bf: BuildFrom[C[Task[T]], T, C[T]],
                        asIterable: C[Task[T]] => Iterable[Task[T]]): Task[C[T]] = flatMap { _ =>
    val empty = bf.newBuilder(tasks)
    Task {
      asIterable(tasks).foldLeft(empty) {
        case (builder, task) => builder.addOne(task.sync())
      }.result()
    }
  }

  /**
   * Converts a sequence of Task[Return] to a Task that returns a sequence of T in parallel. Similar to sequence,
   * but starts a new Task per entry in the sequence. Warning: For large sequences this can be extremely heavy on the
   * CPU. For larger sequences it's recommended to use Stream.par instead.
   */
  def parSequence[T: ClassTag, C[_]](tasks: C[Task[T]])
                                    (implicit bf: BuildFrom[C[Task[T]], T, C[T]],
                                     asIterable: C[Task[T]] => Iterable[Task[T]]): Task[C[T]] = flatMap { _ =>
    val completable = Task.completable[C[T]]
    val total = asIterable(tasks).size
    val array = new Array[T](total)
    val completed = new AtomicInteger(0)

    def add(r: T, index: Int): Unit = {
      array(index) = r
      val finished = completed.incrementAndGet()
      if (finished == total) {
        completable.success(bf.newBuilder(tasks).addAll(array).result())
      }
    }

    asIterable(tasks).zipWithIndex.foreach {
      case (task, index) => task.map { r =>
        array(index) = r
        add(r, index)
      }.start()
    }
    completable
  }

  /**
   * Provides convenience functionality to execute this Task as a scala.concurrent.Future.
   */
  def toFuture(implicit ec: scala.concurrent.ExecutionContext): scala.concurrent.Future[Return] =
    scala.concurrent.Future(this.sync())
}

object Task extends Task[Unit] {
  override protected def invoke(): Unit = ()

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
  override def unit: Task[Unit] = this

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
}