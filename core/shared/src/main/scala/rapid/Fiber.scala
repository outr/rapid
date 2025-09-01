package rapid

import java.util.concurrent.CompletableFuture
import scala.annotation.nowarn
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success}

trait Fiber[+Return] extends Task[Return] {
  /**
   * Unique identifier for this fiber, used for shard distribution in timer wheels
   */
  def id: Long
  
  /**
   * Optional preferred executor for affinity preservation
   */
  def preferredExecutor: Option[Int] = None
  
  override def start: Task[Fiber[Return]] = Task.pure(this)

  /**
   * Attempts to cancel the Fiber. Returns true if successful.
   */
  def cancel: Task[Boolean] = Task.pure(false)

  override def await(): Return = sync()

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

