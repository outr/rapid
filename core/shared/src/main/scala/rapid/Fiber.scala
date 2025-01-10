package rapid

import java.util.concurrent.CompletableFuture
import scala.annotation.nowarn
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success}

trait Fiber[+Return] extends Task[Return] {
  override def start(): Fiber[Return] = this

  /**
   * Attempts to cancel the Fiber. Returns true if successful.
   */
  def cancel(): Task[Boolean] = Task.pure(false)

  override def await(): Return = sync()

  override def toString: String = "Fiber"
}

object Fiber {
  @nowarn()
  def fromFuture[Return](future: Future[Return]): Fiber[Return] = {
    val completable = Task.completable[Return]
    future.onComplete {
      case Success(value) => completable.success(value)
      case Failure(exception) => completable.failure(exception)
    }(scala.concurrent.ExecutionContext.Implicits.global)
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