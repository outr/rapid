package rapid

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.Duration
import scala.util.Try

class FutureFiber[Return](val task: Task[Return]) extends BlockableFiber[Return] {
  private val future: Future[Return] = Future(task.sync())

  override protected def invoke(): Return = await()

  override def await(): Return = await(Duration.Inf).get

  override def await(duration: Duration): Option[Return] = Try(Await.result(future, duration)).toOption
}