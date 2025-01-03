package rapid

import scala.concurrent.Future

class FutureFiber[Return](val task: Task[Return]) extends Fiber[Return] {
  private val future: Future[Return] = Future(task.sync())(Platform.executionContext)

  override def sync(): Return = future.value match {
    case Some(value) => value.get
    case None => throw new RuntimeException("Cannot wait")
  }
}