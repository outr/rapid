package rapid

import rapid.fiber.{FixedThreadPoolFiber, SynchronousFiber}
import rapid.task._
import rapid.trace.Trace
import sourcecode.{Enclosing, File, Line}

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import scala.collection.BuildFrom
import scala.concurrent.TimeoutException
import scala.concurrent.duration.{DurationLong, FiniteDuration}
import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try}

/**
 * Represents a task that can be executed to produce a result of type `Return`.
 *
 * @tparam Return the type of the result produced by this task
 */
trait Task[+Return] {
  protected def trace: Trace

  protected def exec(mode: ExecutionMode): Fiber[Return] = mode match {
    case ExecutionMode.Synchronous => SynchronousFiber(this)
    case ExecutionMode.Asynchronous => FixedThreadPoolFiber(this)
  }

  /**
   * Transforms this task to a pure result.
   *
   * @param value the return value of this Task
   * @tparam T the type of the result produced by the task
   * @return a new task
   */
  def pure[T](value: T): Task[T] =
    FlatMap(this, (_: Return) => Pure(value), Trace.empty)

  /**
   * Defers the execution of the given task.
   *
   * @param task the task to defer
   * @tparam T the type of the result produced by the task
   * @return a new task that defers the execution of the given task
   */
  def defer[T](task: => Task[T]): Task[T] = flatMap(_ => task)

  /**
   * Transforms the result of this task to the result of the supplied function ignoring the previous result.
   *
   * @param f the function to execute
   * @tparam T the type of the result produced by the task
   * @return a new task
   */
  def function[T](f: => T)(implicit file: File, line: Line, enclosing: Enclosing): Task[T] = map(_ => f)

  /**
   * Transforms the result of the task using the given function.
   *
   * @param f the function to transform the result
   * @tparam T the type of the transformed result
   * @return a new task with the transformed result
   */
  def map[T](f: Return => T)(implicit file: File, line: Line, enclosing: Enclosing): Task[T] = {
    val tr = if (Trace.Enabled) Trace(file, line, enclosing) else Trace.empty
    if (Trace.Enabled) {
      // Preserve per-step traces by building as FlatMap → Suspend
      FlatMap(this, (r: Return) => Suspend(() => f(r), tr), tr)
    } else this match {
      case Pure(r) => Suspend(() => f(r), tr)
      case Suspend(pf, _) => Suspend(() => f(pf()), tr)
      case _ => flatMap(r => Suspend(() => f(r), tr))
    }
  }

  /**
   * Similar to map, but does not change the value.
   *
   * @param f the function to apply to underlying value
   * @return Task[Return]
   */
  def foreach(f: Return => Unit)(implicit file: File, line: Line, enclosing: Enclosing): Task[Return] = map { r =>
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
  def flatMap[T](f: Return => Task[T])(implicit file: File, line: Line, enclosing: Enclosing): Task[T] = {
    val tr = if (Trace.Enabled) Trace(file, line, enclosing) else Trace.empty
    FlatMap(this, f, tr)
  }

  def forge[R >: Return, T](forge: Forge[R, T])(implicit file: File, line: Line, enclosing: Enclosing): Task[T] =
    flatMap(forge(_))

  /**
   * Works like flatMap, but ignores the previous value.
   *
   * @param task the next task to process
   * @tparam T the type of the result of the new task
   */
  def next[T](task: => Task[T])(implicit file: File, line: Line, enclosing: Enclosing): Task[T] =
    flatMap(_ => task)

  /**
   * Similar to flatMap, but ignores the return propagating the current return value on.
   *
   * @param f the function to handle the result
   * @return existing signature
   */
  def flatTap(f: Return => Task[_])(implicit file: File, line: Line, enclosing: Enclosing): Task[Return] = flatMap { r =>
    f(r).map(_ => r)
  }

  /**
   * Combines the two tasks to execute at the same time
   *
   * @param that the second task to execute
   */
  def and[T](that: Task[T]): Task[(Return, T)] = Task {
    val f1 = this.start()
    val f2 = that.start()
    val r1 = f1.sync()
    val r2 = f2.sync()
    (r1, r2)
  }

  /**
   * Makes this Task execute exactly once. Any future calls to this Task will return the result of the first execution.
   */
  def singleton: Task[Return] = {
    val triggered = new AtomicBoolean(false)
    val completable = Task.completable[Return]

    Task {
      val active = triggered.compareAndSet(false, true)
      if (active) {
        // Execute the task directly without creating a fiber to avoid hanging
        try {
          val result = this.sync()
          completable.success(result)
          result
        } catch {
          case e: Throwable =>
            completable.failure(e)
            throw e
        }
      } else {
        // Wait for the completable to be completed by the first execution
        completable.sync()
      }
    }
  }

  /**
   * Works like flatTap, but ignores the previous value.
   */
  def effect[T](task: => Task[T])(implicit file: File, line: Line, enclosing: Enclosing): Task[Return] = flatTap(_ => task)

  /**
   * Creates a new Completable task.
   *
   * @tparam T the type of the result produced by the task
   * @return a new Completable task
   */
  def withCompletable[T]: Task[Completable[T]] = map(_ => Task.completable)

  /**
   * Convenience conditional execution of the Task. If the condition is true, the task will execute the instruction set,
   * but if false, it will immediately return upon execution doing nothing. The return is Some(r) if the condition is
   * met and None if the condition is false.
   */
  def when(condition: => Boolean): Task[Option[Return]] = Task.defer {
    if (condition) {
      this.map(Some.apply)
    } else {
      Task.pure(None)
    }
  }

  /**
   * Convenience conditional execution of the Task. If the condition is true, the task will execute the instruction set,
   * but if false, it will return upon execution returning "default".
   */
  def when[R >: Return](condition: => Boolean, default: => R): Task[R] = when(condition)
    .map {
      case Some(r) => r
      case None => default
    }

  /**
   * Convenience method to disable a task from executing without removing it entirely.
   *
   * Simply returns Task.unit instead.
   */
  def disabled: Task[Unit] = Task.unit

  /**
   * Chains the task to a Unit result.
   *
   * @return a new task that returns `Unit` after the existing task completes
   */
  def unit: Task[Unit] = map(_ => ())

  /**
   * Effect to get the current time in milliseconds
   */
  def now: Task[Long] = function(System.currentTimeMillis())

  /**
   * Convenience method to get the time elapsed to execute the task along with the return value.
   */
  def elapsed: Task[(Return, Double)] = Task.defer {
    val start = System.currentTimeMillis()
    map { r =>
      val e = (System.currentTimeMillis() - start) / 1000.0
      r -> e
    }
  }

  /**
   * Uses a timer to capture the elapsed time for the execution of the supplied Task. This is useful for multiple
   * concurrent calls to a block to measure hot spots in performance over time.
   *
   * @param timer the timer to use
   * @param task  the task to time
   * @tparam T the return type
   */
  def timed[T](timer: Timer)(task: => Task[T]): Task[T] = Task.defer {
    val start = System.nanoTime()
    task.guarantee(Task {
      val elapsed = System.nanoTime() - start
      timer._elapsed.addAndGet(elapsed)
    })
  }

  /**
   * Chains a sleep to the end of this task.
   *
   * @param duration the duration to sleep
   * @return a new task that sleeps for the given duration after the existing task completes
   */
  def sleep(duration: FiniteDuration)(implicit file: File, line: Line, enclosing: Enclosing): Task[Return] = {
    val tr = if (Trace.Enabled) Trace(file, line, enclosing) else Trace.empty
    effect(Sleep(duration, tr))
  }

  /**
   * Convenience functionality wrapping sleep to delay until the timeStamp (in millis).
   *
   * @param timeStamp milliseconds since epoch when this should complete
   */
  def schedule(timeStamp: => Long): Task[Return] = sleep {
    val delay = timeStamp - System.currentTimeMillis()
    delay.millis
  }

  /**
   * Convenience functionality for repeated execution.
   *
   * @param repeat the Repeat implementation to use
   */
  def repeat[R >: Return](repeat: Repeat[R]): Task[R] = repeat(this)

  /**
   * Sleeps until the condition is met (returns true) or timeout
   *
   * @param condition      the condition that must return true to proceed
   * @param delay          the delay between tests of condition (defaults to 1 second)
   * @param timeout        the timeout before this condition fails (defaults to 24 hours)
   * @param errorOnTimeout whether to throw a TimeoutException on timeout (defaults to true)
   */
  def condition(condition: Task[Boolean],
                delay: FiniteDuration = 1.second,
                timeout: FiniteDuration = 24.hours,
                errorOnTimeout: Boolean = true): Task[Return] = flatTap { _ =>
    val start = System.currentTimeMillis()
    val timeoutTime = start + timeout.toMillis

    def recurse: Task[Unit] = condition.flatMap {
      case true => Task.unit
      case false if System.currentTimeMillis() >= timeoutTime =>
        if (errorOnTimeout) {
          Task.error(new TimeoutException(s"Condition timed out after $timeout"))
        } else {
          Task.unit
        }
      case false => Task.sleep(delay).next(recurse)
    }

    recurse
  }

  /**
   * Attempts to execute the task and returns either the result or an exception.
   *
   * @return either the result of the task or an exception
   */
  def attempt: Task[Try[Return]] = Task {
    Try(sync())
  }

  /**
   * Creates a new task that raises an error when invoked.
   *
   * @param throwable the exception to raise
   */
  def error[T](throwable: Throwable): Task[T] = next {
    throw throwable
  }

  /**
   * Handles error in task execution.
   *
   * @param f handler
   * @return Task[R]
   */
  def handleError[R >: Return](f: Throwable => Task[R]): Task[R] = attempt
    .flatMap {
      case Success(r) => Task.pure(r)
      case Failure(t) => f(t)
    }

  /**
   * Guarantees execution even if there's an exception at a higher level
   *
   * @param task the task to guarantee invocation of
   */
  def guarantee(task: => Task[Unit]): Task[Return] = attempt
    .flatTap { _ =>
      task
    }
    .map(_.get)

  /**
   * Synchronously (blocking) executes the task and returns the result.
   *
   * @return the result of the task
   */
  def sync(): Return = exec(ExecutionMode.Synchronous).sync()

  /**
   * Synonym for sync(). Allows for clean usage with near transparent invocations.
   *
   * @return the result of the task
   */
  final def apply(): Return = sync()

  /**
   * Starts the task and returns a `Fiber` representing the running task.
   */
  final def start: Task[Fiber[Return]] = Suspend(() => exec(ExecutionMode.Asynchronous), Trace.empty)

  /**
   * Specialized fire-and-forget start that avoids Fiber/latch. Optimized for simple Suspend tasks.
   */
  final def startUnit(): Unit = this match {
    case Suspend(f, _) => rapid.fiber.FixedThreadPoolFiber.execute(new Runnable { override def run(): Unit = f(); })
    case _ => rapid.fiber.FixedThreadPoolFiber.execute(new Runnable { override def run(): Unit = try SynchronousFiber(Task.this).sync() catch { case _: Throwable => () } })
  }

  /**
   * Fire-and-forget execution: schedule on the async executor without returning a Fiber.
   * Useful in benchmarks or cases where you only need completion side-effects.
   */
  final def runAsync(): Unit = {
    rapid.fiber.FixedThreadPoolFiber.execute(new Runnable {
      override def run(): Unit = {
        try rapid.fiber.SynchronousFiber(Task.this).sync() catch { case _: Throwable => () }
      }
    })
  }

  /**
   * Provides convenience functionality to execute this Task as a scala.concurrent.Future.
   */
  def toFuture(implicit ec: scala.concurrent.ExecutionContext): scala.concurrent.Future[Return] =
    scala.concurrent.Future(this.sync())

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
    if (total == 0) {
      completable.success(bf.newBuilder(tasks).result())
    } else {
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
    }
    completable
  }
}

object Task extends UnitTask {
  override def pure[T](value: T): Task[T] = Pure(value)

  def apply[T](f: => T)(implicit file: File, line: Line, enclosing: Enclosing): Task[T] =
    Suspend(() => f, Trace(file, line, enclosing))

  /** No-trace constructor to avoid sourcecode implicits in hot paths */
  def suspend[T](f: () => T): Task[T] = Suspend(f, Trace.empty)

  def completable[T](implicit file: File, line: Line, enclosing: Enclosing): Completable[T] =
    Completable[T](Trace(file, line, enclosing))
}