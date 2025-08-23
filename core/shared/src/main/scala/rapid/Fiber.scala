package rapid

import rapid.concurrency.TaskExecution

import java.util.concurrent.CompletableFuture
import scala.annotation.nowarn
import scala.concurrent.{CancellationException, Future}
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.{Failure, Success, Try}

class Fiber[+Return](task: Task[Return]) extends Task[Return] {
  private[this] val execution = new TaskExecution[Return](task)
  execution.async()

  override def start: Task[Fiber[Return]] = Task.pure(this)

  override def sync(): Return = execution.completable.sync()

  def await(timeout: FiniteDuration,
            delay: FiniteDuration = 100.millis): Option[Return] = {
    Task.condition(
      condition = Task(isComplete),
      delay = delay,
      timeout = timeout,
      errorOnTimeout = false
    ).sync()
    if (isComplete) {
      execution.completable.result.get match {
        case Success(r) => Some(r)
        case Failure(t) => throw t
      }
    } else {
      None
    }
  }

  /**
   * Attempts to cancel the Fiber. Returns true if successful.
   */
  def cancel: Task[Boolean] = Task {
    execution.cancelled = true
    true
  }

  /**
   * Checks if this fiber has completed execution.
   */
  def isComplete: Boolean = execution.completable.isComplete

  /**
   * Registers a callback to be called when this fiber completes.
   */
  def onComplete(callback: Try[Return] => Unit): Unit = {
    if (isComplete) {
      callback(execution.completable.result.get)
    } else {
      execution.completable.onComplete(callback)
    }
  }

  override def toString: String = s"Fiber(${getClass.getSimpleName})"
}

object Fiber {
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