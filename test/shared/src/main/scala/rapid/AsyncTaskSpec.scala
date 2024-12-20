package rapid

import scala.concurrent.Future

import scala.language.implicitConversions

trait AsyncTaskSpec {
  implicit def task2Future[Return](task: Task[Return])
                                  (implicit ec: scala.concurrent.ExecutionContext): Future[Return] = task.toFuture
}
