package rapid

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success, Try}

class FutureBlockableFiber[Return](val task: Task[Return]) extends BlockableFiber[Return] {
  private val future: Future[Return] = Future(task.sync())(Platform.executionContext)

  override protected def invoke(): Return = await()

  override def await(): Return = Await.result(future, Duration.Inf)

  override def await(duration: Duration): Option[Return] = Try(Await.result(future, duration)) match {
    case Success(value) => Some(value)
    case Failure(_) => None
  }
}