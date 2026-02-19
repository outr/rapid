package rapid.fiber

import rapid.{Blockable, Fiber, Task}

import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.concurrent.{Await, ExecutionContext, Future, TimeoutException}
import scala.util.{Failure, Success, Try}

class FutureBlockableFiber[Return](task: Task[Return])(implicit ec: ExecutionContext)
  extends Fiber[Return] with Blockable[Return] {

  private val fut: Future[Return] = Future(SynchronousFiber(task).awaitBlocking())(ec)

  override def sync(): Return = Await.result(fut, Duration.Inf)

  override def await(): Return = sync()

  override def await(duration: FiniteDuration): Option[Return] =
    try Some(Await.result(fut, duration))
    catch { case _: TimeoutException => None }

  override def join: Task[Return] = {
    val c = Task.completable[Return]
    onComplete {
      case Success(v) => c.success(v)
      case Failure(t) => c.failure(t)
    }
    c
  }

  override def onComplete(f: Try[Return] => Unit): Unit = {
    fut.onComplete(f)(ec)
  }
}
