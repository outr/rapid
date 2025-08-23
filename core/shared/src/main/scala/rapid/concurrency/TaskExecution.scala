package rapid.concurrency

import rapid.task._
import rapid.{Fiber, Forge, Task}

import java.util
import scala.annotation.tailrec
import scala.util.{Failure, Success}

class TaskExecution[Return](task: Task[Return]) {
  @volatile private var previous: Any = ()
  private val stack = new util.ArrayDeque[Any]
  @volatile private var cancellable: Cancellable = _

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

  def async(): Unit = {
    cancellable = ConcurrencyManager.active.fire(this)
  }

  def schedule(delay: scala.concurrent.duration.FiniteDuration): Unit = {
    cancellable = ConcurrencyManager.active.schedule(delay, this)
  }

  def cancelAll(): Unit = {
    cancelled = true
    if (cancellable != null) {
      cancellable.cancel()
      cancellable = null
    }
    // Complete the task with a CancellationException so sync() calls can return
    if (!completable.isComplete) {
      completable.failure(new scala.concurrent.CancellationException("Task was cancelled"))
    }
  }

  @tailrec
  final def execute(sync: Boolean): Unit = if (stack.isEmpty) {
    completable.success(previous.asInstanceOf[Return])
  } else if (cancelled) {
    // Task was cancelled, complete with CancellationException
    completable.failure(new scala.concurrent.CancellationException("Task was cancelled"))
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
          schedule(d)
          recurse = false
        case c: CompletableTask[_] if sync => previous = c.sync()
        case c: CompletableTask[_] =>
          c.onComplete { t =>
            previous = t
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