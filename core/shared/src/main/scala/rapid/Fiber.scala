package rapid

import java.util.concurrent.CompletableFuture
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

trait Fiber[Return] extends Task[Return] {
  override def start(): Fiber[Return] = this

  /**
   * Attempts to cancel the Fiber. Returns true if successful.
   */
  def cancel(): Task[Boolean] = Task.pure(false)

  override def await(): Return = invoke()
}

object Fiber {
  def fromFuture[Return](future: Future[Return]): Fiber[Return] =
    () => Await.result(future, 24.hours)

  def fromFuture[Return](future: CompletableFuture[Return]): Fiber[Return] = {
    val completable = new Task.Completable[Return]
    future.whenComplete {
      case (_, error) if error != null => completable.failure(error)
      case (r, _) => completable.success(r)
    }
    completable.start()
  }
}