package rapid

import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.Try

trait Fiber[Return] extends Task[Return] {
  override def start(): Fiber[Return] = this

  override def await(): Return = invoke()
}

object Fiber {
  def fromFuture[Return](future: Future[Return]): Fiber[Return] =
    () => Await.result(future, Duration.Inf)

  def fromFuture[Return](future: CompletableFuture[Return]): Fiber[Return] = {
    val completable = Task.completable[Return]
    future.whenComplete {
      case (_, error) if error != null => completable.failure(error)
      case (r, _) => completable.success(r)
    }
    completable.start()
  }
}