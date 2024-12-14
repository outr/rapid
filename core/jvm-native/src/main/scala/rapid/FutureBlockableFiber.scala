package rapid

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success, Try}

class FutureBlockableFiber[Return](val task: Task[Return]) extends Blockable[Return] with Fiber[Return] {
  private val future: Future[Return] = Future(task.sync())(Platform.executionContext)

  override protected def invoke(): Return = await()

  override def await(): Return = Await.result(future, 24.hours)

  override def await(duration: FiniteDuration): Option[Return] = Try(Await.result(future, duration)) match {
    case Success(value) => Some(value)
    case Failure(_) => None
  }
}