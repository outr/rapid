package rapid

import org.scalatest.Assertion

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration._
import scala.language.implicitConversions
import scala.util.{Failure, Success}

trait AsyncTaskSpec extends org.scalatest.AsyncTestSuite {
  /** Default per-test timeout to prevent hanging specs. */
  implicit protected val testTimeout: FiniteDuration = 1.minute

  override implicit def executionContext: ExecutionContext = Platform.executionContext

  implicit def task2Future[Return](task: Task[Return]): Future[Return] = {
    val p = Promise[Return]()

    val fiber = Platform.createFiber(task)
    fiber.onComplete {
      case Success(v) => p.trySuccess(v)
      case Failure(t) => p.tryFailure(t)
    }

    Platform.scheduleDelay(testTimeout.toMillis) { () =>
      p.tryFailure(new java.util.concurrent.TimeoutException("Async test timed out"))
    }

    p.future
  }

  implicit class TaskTestExtras[Return](task: Task[Return]) {
    def succeed: Task[Assertion] = task.map(_ => org.scalatest.Assertions.succeed)
  }
}
