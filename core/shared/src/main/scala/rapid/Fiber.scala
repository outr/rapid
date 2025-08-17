package rapid

import rapid.task.{FiberTask, PureTask, CompletableTask}
import rapid.{Task, TaskLike}

import java.util.concurrent.CompletableFuture
import scala.annotation.nowarn
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
 * Represents a computation that can be started and awaited.
 */
trait Fiber[+Return] extends TaskLike[Return] {

  /** Returns a Task that completes with this Fiber. */

  /** Attempts to cancel the Fiber. Override if supported. */
  def cancel: Task[Boolean] = PureTask(() => false)

  /** Await and return the result. */
  override def await(): Return = sync()

  /** Await synchronously (alias for await). */
  def sync(): Return
}

object Fiber {

  /** Convert a Scala Future to a Fiber. */
  @nowarn
  def fromFuture[Return](future: Future[Return])(implicit ec: ExecutionContext): Fiber[Return] = {
    val completable = Task.completable[Return]
    future.onComplete {
      case Success(value)     => completable.success(value)
      case Failure(exception) => completable.failure(exception)
    }
    FiberTask(completable)
  }

  /** Convert a Java CompletableFuture to a Fiber. */
  def fromFuture[Return](future: CompletableFuture[Return]): Fiber[Return] = {
    val completable = Task.completable[Return]
    future.whenComplete {
      case (_, error) if error != null => completable.failure(error)
      case (result, _)                 => completable.success(result)
    }
    FiberTask(completable)
  }
}
