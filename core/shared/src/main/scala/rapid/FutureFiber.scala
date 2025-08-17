package rapid

import scala.concurrent.{Await, Future}
import scala.concurrent.duration.{Duration, FiniteDuration}

class FutureFiber[Return](val task: Task[Return]) extends Fiber[Return] {
  private val future: Future[Return] = Future(task.sync())(Platform.executionContext)

  override def sync(): Return = future.value match {
    case Some(value) => value.get
    case None        => throw new RuntimeException("Cannot wait")
  }

  def await(duration: FiniteDuration): Option[Return] = {
    try {
      Some(Await.result(future, duration))
    } catch {
      case _: java.util.concurrent.TimeoutException | _: scala.concurrent.TimeoutException => None
      case e: Throwable => throw e
    }
  }

  override def await(): Return = Await.result(future, Duration.Inf)

  override def start: TaskLike[Return] = new TaskLike[Return] {
    def sync(): Return = FutureFiber.this.sync()
    def await(): Return = FutureFiber.this.await()
    def start: TaskLike[Return] = this
  }
}
