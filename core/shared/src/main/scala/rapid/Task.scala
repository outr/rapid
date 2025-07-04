package rapid

import rapid.monitor.TaskMonitor
import rapid.task.{CompletableTask, ErrorTask, FlatMapTask, PureTask, SingleTask, Taskable, UnitTask}

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import scala.annotation.tailrec
import scala.collection.BuildFrom
import scala.concurrent.duration.{DurationLong, FiniteDuration}
import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try}

/**
 * Represents a task that can be executed to produce a result of type `Return`.
 *
 * @tparam Return the type of the result produced by this task
 */
trait Task[+Return] extends Any {
  /**
   * Synchronously (blocking) executes the task and returns the result.
   *
   * @return the result of the task
   */
  def sync(): Return = {
    val stack = new java.util.ArrayDeque[Any]()
    stack.push(this)

    var previous: Any = ()

    while (!stack.isEmpty) {
      val head = stack.pop()

      Task.monitor.foreach { m =>
        head match {
          case t: Task[_] => m.start(t)
          case _ => // Ignore Forge
        }
      }

      try {
        head match {
          case _: UnitTask => previous = ()
          case PureTask(value) => previous = value
          case SingleTask(f) => previous = f()
          case t: Taskable[_] => previous = t.toTask.sync()
          case ErrorTask(throwable) => throw throwable
          case c: CompletableTask[_] => previous = c.sync()
          case f: Fiber[_] => previous = f.sync()
          case f: Forge[_, _] =>
            stack.push(f.asInstanceOf[Forge[Any, Any]](previous))
          case FlatMapTask(source, forge) =>
            stack.push(forge)  // Push forge first so that source executes first
            stack.push(source)
          case _ => throw new UnsupportedOperationException(s"Unsupported task: $head (${head.getClass.getName})")
        }

        Task.monitor.foreach { m =>
          head match {
            case t: Task[_] => m.success(t, previous)
            case _ => // Ignore Forge
          }
        }
      } catch {
        case throwable: Throwable =>
          Task.monitor.foreach { m =>
            head match {
              case t: Task[_] => m.error(t, throwable)
              case _ => // Ignore Forge
            }
          }
          throw throwable
      }
    }

    previous.asInstanceOf[Return]
  }

  /**
   * Synonym for sync(). Allows for clean usage with near transparent invocations.
   *
   * @return the result of the task
   */
  def apply(): Return = sync()

  /**
   * Starts the task and returns a `Fiber` representing the running task.
   */
  def start: Task[Fiber[Return]] = Task {
    val f = Platform.createFiber(this)
    Task.monitor.foreach(_.fiberCreated(f, this))
    f
  }

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
    Try(sync())
  }

  /**
   * Creates a new task that raises an error when invoked.
   *
   * @param throwable the exception to raise
   * @return a new Error task
   */
  def error[T](throwable: Throwable): Task[T] = next {
    val e = ErrorTask[T](throwable)
    Task.monitor.foreach(_.created(e))
    e
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
   * Transforms the result of the task using the given function.
   *
   * @param f the function to transform the result
   * @tparam T the type of the transformed result
   * @return a new task with the transformed result
   */
  def map[T](f: Return => T): Task[T] = this match {
    case PureTask(value) => PureTask(f(value))  // Directly apply transformation
    case SingleTask(g) => SingleTask(() => f(g()))  // Apply transformation inline
    case _ => FlatMapTask(this, Forge[Return, T](i => Task(f(i))))
  }


  /**
   * Transforms this task to a pure result.
   *
   * @param value the return value of this Task
   * @tparam T the type of the result produced by the task
   * @return a new task
   */
  def pure[T](value: T): Task[T] = next {
    val t = PureTask(value)
    Task.monitor.foreach(_.created(t))
    t
  }

  /**
   * Transforms the result of this task to the result of the supplied function ignoring the previous result.
   *
   * @param f the function to execute
   * @tparam T the type of the result produced by the task
   * @return a new task
   */
  def function[T](f: => T): Task[T] = map(_ => f)

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
  def flatMap[T](f: Return => Task[T]): Task[T] = this match {
    case PureTask(value) => f(value)  // Direct execution for pure values
    case _ => FlatMapTask(this, Forge(f))
  }

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
   * Works like flatMap, but ignores the previous value.
   *
   * @param task the next task to process
   * @tparam T the type of the result of the new task
   */
  def next[T](task: => Task[T]): Task[T] = flatMap(_ => task)

  /**
   * Chains a sleep to the end of this task.
   *
   * @param duration the duration to sleep
   * @return a new task that sleeps for the given duration after the existing task completes
   */
  def sleep(duration: => FiniteDuration): Task[Return] = flatTap { r =>
    val millis = duration.toMillis
    if (millis > 0L) {
      Task(Thread.sleep(millis))
    } else {
      Task.unit
    }
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
   * Effect to get the current time in milliseconds
   */
  def now: Task[Long] = flatMap(_ => Task(System.currentTimeMillis()))

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
   * Defers the execution of the given task.
   *
   * @param task the task to defer
   * @tparam T the type of the result produced by the task
   * @return a new task that defers the execution of the given task
   */
  def defer[T](task: => Task[T]): Task[T] = flatMap(_ => task)

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
  def unit: Task[Unit] = map(_ => task.UnitTask)

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

  /**
   * Creates a new Completable task.
   *
   * @tparam T the type of the result produced by the task
   * @return a new Completable task
   */
  def withCompletable[T]: Task[CompletableTask[T]] = map(_ => Task.completable)

  /**
   * Provides convenience functionality to execute this Task as a scala.concurrent.Future.
   */
  def toFuture(implicit ec: scala.concurrent.ExecutionContext): scala.concurrent.Future[Return] =
    scala.concurrent.Future(this.sync())
}

object Task extends task.UnitTask {
  var monitor: Opt[TaskMonitor] = Opt.Empty

  def apply[T](f: => T): Task[T] = {
    val t = SingleTask(() => f)
    Task.monitor.foreach(_.created(t))
    t
  }

  def completable[Return]: CompletableTask[Return] = {
    val c = new CompletableTask[Return]
    Task.monitor.foreach(_.created(c))
    c
  }
}