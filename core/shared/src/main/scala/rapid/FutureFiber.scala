package rapid

import scala.concurrent.Future
import scala.util.{Failure, Try}

class FutureFiber[Return](val task: Task[Return]) extends Fiber[Return] {
  private val future: Future[Return] = Future(task.sync())(Platform.executionContext)

  override protected def invoke(): Return = await()

  override def attempt(): Try[Return] = future.value match {
    case Some(value) => value
    case None => Failure(new RuntimeException("Cannot wait"))
  }

  override def await(): Return = attempt().get
}