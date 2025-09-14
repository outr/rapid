package rapid

import rapid.monitor.TaskMonitor
import rapid.task.{CommonTasks, CompletableTask, DirectFlatMapTask, ErrorTask, FlatMapTask, PureTask, SingleTask, SleepTask, Taskable, UnitTask}

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import scala.annotation.tailrec
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
trait Task[+Return] extends Any {
  /**
   * Synchronously (blocking) executes the task and returns the result.
   *
   * @return the result of the task
   */
  def sync(): Return = {
    // Lazy ArrayDeque allocation - only create when needed
    // This avoids millions of allocations for simple FlatMap chains
    var stack: java.util.ArrayDeque[Any] = null
    var current: Any = this
    var previous: Any = ()
    
    // Cache monitor reference to avoid volatile read overhead in tight loops
    val monitor = Task.monitor

    while (current != null || (stack != null && !stack.isEmpty)) {
      val head = if (current != null) {
        val c = current
        current = null
        c
      } else {
        stack.pop()
      }

      if (monitor != null) {
        head match {
          case t: Task[_] => monitor.start(t)
          case _ => // Ignore Forge
        }
      }

      try {
        // HOT PATH: Use explicit instanceof checks for common cases (JIT optimizes these better)
        if (head.isInstanceOf[DirectFlatMapTask[_, _]]) {
          val dft = head.asInstanceOf[DirectFlatMapTask[Any, Any]]
          val source = dft.source
          val func = dft.f
          
          // FAST PATH: Detect deep accumulator chains and execute tail-recursively
          // This handles foldLeft patterns that create deep DirectFlatMapTask chains
          if (source.isInstanceOf[DirectFlatMapTask[_, _]]) {
            // Count chain depth and check if it's an accumulator pattern
            var depth = 0
            var currentChain: Any = source
            var isAccumulatorPattern = true
            
            // Quick depth check (limit to avoid slow traversal)
            while (depth < 100 && currentChain.isInstanceOf[DirectFlatMapTask[_, _]]) {
              currentChain = currentChain.asInstanceOf[DirectFlatMapTask[Any, Any]].source
              depth += 1
            }
            
            // If we have a deep chain, execute it tail-recursively
            if (depth >= 50) {
              // Collect all functions in the chain
              val functions = new java.util.ArrayList[Any => Task[Any]](depth + 1)
              functions.add(func)
              
              currentChain = source
              while (currentChain.isInstanceOf[DirectFlatMapTask[_, _]]) {
                val chainDft = currentChain.asInstanceOf[DirectFlatMapTask[Any, Any]]
                functions.add(chainDft.f)
                currentChain = chainDft.source
              }
              
              // Execute from the bottom up
              var result: Any = currentChain match {
                case PureTask(v) => v
                case SingleTask(f) => 
                  try { f() } catch { case e: Throwable => throw e }
                case _ =>
                  // Not an accumulator pattern, fall back to regular execution
                  if (stack == null) stack = new java.util.ArrayDeque[Any](32)
                  stack.push(func)
                  stack.push(source)
                  null
              }
              
              if (result != null) {
                // Execute all functions in reverse order (bottom-up)
                var i = functions.size() - 1
                while (i >= 0) {
                  val f = functions.get(i)
                  val nextTask = f(result)
                  result = nextTask match {
                    case PureTask(v) => v
                    case SingleTask(g) => 
                      try { g() } catch { case e: Throwable => throw e }
                    case _ =>
                      // Complex task, can't optimize further
                      current = nextTask
                      var j = i - 1
                      while (j >= 0) {
                        current = DirectFlatMapTask(current.asInstanceOf[Task[Any]], functions.get(j))
                        j -= 1
                      }
                      i = -1 // Exit outer loop
                      null
                  }
                  if (result != null) i -= 1
                }
                if (result != null) previous = result
              }
            } else if (source.isInstanceOf[PureTask[_]]) {
              // DirectFlatMap with PureTask - execute immediately
              val value = source.asInstanceOf[PureTask[Any]].value
              current = func(value)
            } else if (source.isInstanceOf[SingleTask[_]]) {
              // DirectFlatMap with SingleTask - execute inline
              try {
                val result = source.asInstanceOf[SingleTask[Any]].f()
                current = func(result)
              } catch {
                case e: Throwable => current = ErrorTask(e)
              }
            } else {
              // Default handling for other source types
              if (stack == null) stack = new java.util.ArrayDeque[Any](32)
              stack.push(func)
              stack.push(source)
            }
          } else {
            // Default DirectFlatMap handling
            if (stack == null) stack = new java.util.ArrayDeque[Any](32)
            stack.push(func)
            stack.push(source)
          }
        } else if (head.isInstanceOf[PureTask[_]]) {
          previous = head.asInstanceOf[PureTask[Any]].value
        } else if (head.isInstanceOf[SingleTask[_]]) {
          previous = head.asInstanceOf[SingleTask[Any]].f()
        } else if (head.isInstanceOf[Function1[_, _]]) {
          // Raw function from DirectFlatMapTask
          if (stack == null) stack = new java.util.ArrayDeque[Any](32)
          stack.push(head.asInstanceOf[Any => Task[Any]](previous))
        } else {
          // Fall back to pattern matching for less common cases
          head match {
            case _: UnitTask => previous = ()
            case SleepTask(d) => 
              val millis = d.toMillis
              if (millis > 0L) Thread.sleep(millis)
              previous = ()
            case t: Taskable[_] => 
              if (stack == null) stack = new java.util.ArrayDeque[Any](32)
              stack.push(t.toTask)
            case ErrorTask(throwable) => throw throwable
            case c: CompletableTask[_] => previous = c.sync()
            case f: Fiber[_] => previous = f.sync()
            case f: Forge[_, _] => 
              if (stack == null) stack = new java.util.ArrayDeque[Any](32)
              stack.push(f.asInstanceOf[Forge[Any, Any]](previous))
            case FlatMapTask(PureTask(value), forge) =>
              current = forge.asInstanceOf[Forge[Any, Any]](value)
            case FlatMapTask(SleepTask(d), forge) =>
              val millis = d.toMillis
              if (millis > 0L) Thread.sleep(millis)
              current = forge.asInstanceOf[Forge[Any, Any]](())
            case FlatMapTask(source, forge) =>
              if (stack == null) stack = new java.util.ArrayDeque[Any](32)
              stack.push(forge)
              stack.push(source)
            case _ => throw new UnsupportedOperationException(s"Unsupported task: $head (${head.getClass.getName})")
          }
        }

        if (monitor != null) {
          head match {
            case t: Task[_] => monitor.success(t, previous)
            case _ => // Ignore Forge
          }
        }
      } catch {
        case throwable: Throwable =>
          if (monitor != null) {
            head match {
              case t: Task[_] => monitor.error(t, throwable)
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
    if (Task.monitor != null) Task.monitor.fiberCreated(f, this)
    f
  }

  /**
   * Starts the task ignoring the result. This can be somewhat faster than start
   * as the fiber is dropped.
   */
  def startAndForget(): Unit = Platform.fireAndForget(this)

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
    if (Task.monitor != null) Task.monitor.created(e)
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
    case PureTask(value) => 
      val result = f(value)
      // Use cached PureTask for common values
      result match {
        case i: Int if i >= -128 && i <= 127 => 
          CommonTasks.pureInt(i).asInstanceOf[Task[T]]
        case b: Boolean => 
          CommonTasks.pureBoolean(b).asInstanceOf[Task[T]]
        case _ => 
          PureTask(result)
      }
    case SingleTask(g) => SingleTask(() => f(g())) // Apply transformation inline
    case ErrorTask(throwable) => ErrorTask(throwable) // Error propagates unchanged
    case SleepTask(d) => 
      // Sleep returns Unit, so we know the type - optimize by chaining
      FlatMapTask(SleepTask(d), Forge[Unit, T](_ => PureTask(f(().asInstanceOf[Return]))))
    case _ => FlatMapTask(this, Forge[Return, T](i => PureTask(f(i))))
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
    if (Task.monitor != null) Task.monitor.created(t)
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
    case PureTask(value) => f(value) // Direct execution for pure values
    case _: UnitTask => f(().asInstanceOf[Return]) // Avoid building extra DirectFlatMapTask for Unit
    case ErrorTask(throwable) => ErrorTask(throwable) // Error propagates unchanged
    case SleepTask(d) => 
      // Sleep returns Unit, optimize by using DirectFlatMapTask with Unit function
      DirectFlatMapTask(SleepTask(d), (_: Unit) => f(().asInstanceOf[Return]))
    case _ => DirectFlatMapTask(this, f) // Zero-allocation path: store raw function directly
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
   * Works like flatTap, but ignores the previous value.
   */
  def effect[T](task: => Task[T]): Task[Return] = flatTap(_ => task)

  /**
   * Chains a sleep to the end of this task.
   *
   * @param duration the duration to sleep
   * @return a new task that sleeps for the given duration after the existing task completes
   */
  def sleep(duration: => FiniteDuration): Task[Return] = effect(SleepTask(duration))

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
    val it = asIterable(tasks)
    val total = it.size

    if (total == 0) {
      completable.success(bf.newBuilder(tasks).result())
    } else {
      val array = new Array[T](total)
      val successed = new AtomicInteger(0)
      val done = new AtomicBoolean(false)

      // Optionally keep fibers to cancel on failure
      val fibers = new Array[Fiber[_]](total)

      def tryCompleteSuccess(): Unit = {
        if (!done.get() && successed.get() == total && done.compareAndSet(false, true)) {
          // Build in original collection shape
          val b = bf.newBuilder(tasks)
          var i = 0
          while (i < total) {
            b += array(i); i += 1
          }
          completable.success(b.result())
        }
      }

      def failOnce(t: Throwable): Unit = {
        if (done.compareAndSet(false, true)) {
          completable.failure(t)
          // Best-effort cancel remaining
          var i = 0
          while (i < fibers.length) {
            val f = fibers(i)
            if (f != null) f.cancel.startAndForget()
            i += 1
          }
        }
      }

      it.zipWithIndex.foreach { case (task, idx) =>
        // observe success/failure explicitly
        val observed: Task[Unit] =
          task.attempt.map {
            case scala.util.Success(v) =>
              array(idx) = v
              val finished = successed.incrementAndGet()
              if (finished == total) tryCompleteSuccess()
            case scala.util.Failure(e) =>
              failOnce(e)
          }

        // start and keep the fiber to allow cancellation
        fibers(idx) = observed.start()
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
  def toFuture(implicit ec: scala.concurrent.ExecutionContext): scala.concurrent.Future[Return] = {
    val promise = scala.concurrent.Promise[Return]()
    
    // Start the task asynchronously via a fiber
    val fiber = Platform.createFiber(this)
    
    // Use a lightweight async execution to complete the promise
    ec.execute(() => {
      try {
        // The fiber's sync() will use work-stealing if available
        promise.success(fiber.sync())
      } catch {
        case e: Throwable => promise.failure(e)
      }
    })
    
    promise.future
  }
}

object Task extends task.UnitTask {
  var monitor: TaskMonitor = _

  def apply[T](f: => T): Task[T] = {
    val t = SingleTask(() => f)
    if (monitor != null) monitor.created(t)
    t
  }
  
  /**
   * Optimized Task.pure that uses cached instances for common values
   */
  override def pure[T](value: T): Task[T] = value match {
    case i: Int if i >= -128 && i <= 127 => 
      CommonTasks.pureInt(i).asInstanceOf[Task[T]]
    case b: Boolean => 
      CommonTasks.pureBoolean(b).asInstanceOf[Task[T]]
    case null => 
      CommonTasks.PURE_NULL.asInstanceOf[Task[T]]
    case "" => 
      CommonTasks.PURE_EMPTY_STRING.asInstanceOf[Task[T]]
    case _ => 
      val t = PureTask(value)
      if (monitor != null) monitor.created(t)
      t
  }

  def completable[Return]: CompletableTask[Return] = {
    val c = new CompletableTask[Return]
    if (monitor != null) monitor.created(c)
    c
  }
}
