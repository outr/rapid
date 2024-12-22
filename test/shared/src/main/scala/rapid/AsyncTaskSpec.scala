package rapid

import org.scalatest.Assertion

import scala.concurrent.Future
import scala.language.implicitConversions

trait AsyncTaskSpec {
  implicit def task2Future[Return](task: Task[Return])
                                  (implicit ec: scala.concurrent.ExecutionContext): Future[Return] = task.toFuture

  implicit class TaskTestExtras[Return](task: Task[Return]) {
    def succeed: Task[Assertion] = task.map(_ => org.scalatest.Assertions.succeed)
  }
}