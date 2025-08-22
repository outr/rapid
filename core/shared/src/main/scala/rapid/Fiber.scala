package rapid

import rapid.task.{CompletableTask, ErrorTask, FlatMapTask, PureTask, SingleTask, SleepTask, Taskable, UnitTask}

import java.util
import java.util.concurrent.{CompletableFuture, Executors, ScheduledExecutorService, ThreadFactory, TimeUnit}
import java.util.concurrent.atomic.AtomicLong
import scala.annotation.{nowarn, tailrec}
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success, Try}

class Fiber[+Return](task: Task[Return]) extends Task[Return] with Blockable[Return] {
  private[this] val execution = new TaskExecution[Return](task)
  execution.async()

  override def start: Task[Fiber[Return]] = Task.pure(this)

  /**
   * Attempts to cancel the Fiber. Returns true if successful.
   */
  def cancel: Task[Boolean] = Task {
    execution.cancelled = true
    true
  }

  override def await(): Return = sync()

  override def await(duration: FiniteDuration): Option[Return] = ???

  override def toString: String = s"Fiber(${getClass.getSimpleName})"
}

object Fiber {
  def main(args: Array[String]): Unit = {
    val i = Task {
      println("RUNNING!")
      5 * 5
    }
    i.start()
    println(i.await())
  }

  @nowarn()
  def fromFuture[Return](future: Future[Return])(implicit ec: scala.concurrent.ExecutionContext): Fiber[Return] = {
    val completable = Task.completable[Return]
    future.onComplete {
      case Success(value) => completable.success(value)
      case Failure(exception) => completable.failure(exception)
    }
    completable.start()
  }

  def fromFuture[Return](future: CompletableFuture[Return]): Fiber[Return] = {
    val completable = Task.completable[Return]
    future.whenComplete {
      case (_, error) if error != null => completable.failure(error)
      case (r, _) => completable.success(r)
    }
    completable.start()
  }
}

trait ConcurrencyManager {
  def schedule(delay: FiniteDuration, execution: TaskExecution[_]): Unit
  def fire(execution: TaskExecution[_]): Unit

  def sync[Return](task: Task[Return]): Return = new TaskExecution[Return](task).sync()
}

object ConcurrencyManager {
  var active: ConcurrencyManager = FixedThreadPoolConcurrencyManager
}

object FixedThreadPoolConcurrencyManager extends ConcurrencyManager {
  private val counter = new AtomicLong(0L)

  private lazy val threadFactory = new ThreadFactory {
    override def newThread(r: Runnable): Thread = {
      val thread = new Thread(r)
      thread.setName(s"rapid-ft-${counter.incrementAndGet()}")
      thread.setDaemon(true)
      thread
    }
  }

  private lazy val executor = Executors.newFixedThreadPool(
    math.max(Runtime.getRuntime.availableProcessors(), 4),
    threadFactory
  )

  private lazy val scheduledExecutor: ScheduledExecutorService = Executors.newScheduledThreadPool(
    math.max(Runtime.getRuntime.availableProcessors() / 2, 2),
    new ThreadFactory {
      override def newThread(r: Runnable): Thread = {
        val thread = new Thread(r)
        thread.setName(s"rapid-scheduler-${counter.incrementAndGet()}")
        thread.setDaemon(true)
        thread
      }
    }
  )

  override def schedule(delay: FiniteDuration, execution: TaskExecution[_]): Unit = {
    scheduledExecutor.schedule(() => Try(execution.async()), delay.toMillis, TimeUnit.MILLISECONDS)
  }

  override def fire(execution: TaskExecution[_]): Unit = executor.submit(() => Try(execution.execute(sync = false)))
}

object VirtualThreadConcurrencyManager extends ConcurrencyManager {
  private val counter = new AtomicLong(0L)
  private val zero = 0.seconds

  override def schedule(delay: FiniteDuration,
                        execution: TaskExecution[_]): Unit = Thread
    .ofVirtual()
    .name(s"rapid-${counter.incrementAndGet()}")
    .start(() => {
      if (!execution.cancelled) {
        Thread.sleep(delay.toMillis)
      }
      if (!execution.cancelled) {
        execution.execute(sync = false)
      }
    })

  override def fire(execution: TaskExecution[_]): Unit = schedule(zero, execution)
}

class TaskExecution[Return](task: Task[Return]) {
  @volatile private var previous: Any = ()
  private val stack = new util.ArrayDeque[Any]

  @volatile var cancelled = false

  stack.push(task)

  val completable: CompletableTask[Return] = Task.completable[Return]

  def sync(): Return = {
    execute(sync = true)
    completable.result.get match {
      case Success(r) => r
      case Failure(t) => throw t
    }
  }

  def async(): Unit = ConcurrencyManager.active.fire(this)

  @tailrec
  final def execute(sync: Boolean): Unit = if (stack.isEmpty) {
    completable.success(previous.asInstanceOf[Return])
  } else {
    val head = stack.pop()

    if (Task.monitor != null) {
      head match {
        case t: Task[_] => Task.monitor.start(t)
        case _ => // Ignore Forge
      }
    }

    var recurse = true

    try {
      head match {
        case SleepTask(d) if sync => Thread.sleep(d.toMillis)
        case SleepTask(d) =>
          ConcurrencyManager.active.schedule(d, this)
          recurse = false
        case c: CompletableTask[_] if sync => previous = c.sync()
        case c: CompletableTask[_] =>
          c.onSuccess { r =>
            previous = r
            async()
          }
          recurse = false
        case _: UnitTask => previous = ()
        case PureTask(value) => previous = value
        case SingleTask(f) => previous = f()
        case t: Taskable[_] => stack.push(t.toTask)
        case ErrorTask(throwable) => throw throwable
        case f: Fiber[_] => previous = f.sync()
        case f: Forge[_, _] => stack.push(f.asInstanceOf[Forge[Any, Any]](previous))
        case FlatMapTask(source, forge) =>
          stack.push(forge) // Push forge first so that source executes first
          stack.push(source)
        case _ => throw new UnsupportedOperationException(s"Unsupported task: $head (${head.getClass.getName})")
      }

      if (Task.monitor != null) {
        head match {
          case t: Task[_] => Task.monitor.success(t, previous)
          case _ => // Ignore Forge
        }
      }
    } catch {
      case throwable: Throwable =>
        recurse = false
        if (Task.monitor != null) {
          head match {
            case t: Task[_] => Task.monitor.error(t, throwable)
            case _ => // Ignore Forge
          }
        }
        completable.failure(throwable)
    }

    if (recurse) execute(sync)
  }
}