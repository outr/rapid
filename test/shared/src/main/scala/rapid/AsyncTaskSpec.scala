package rapid

import org.scalatest.Assertion

import scala.concurrent.{Future, Promise}
import scala.concurrent.duration._
import scala.language.implicitConversions
import scala.util.{Failure, Success}

trait AsyncTaskSpec {
  /** Default per-test timeout to prevent hanging specs. */
  implicit protected val testTimeout: FiniteDuration = 1.minute

  implicit def task2Future[Return](task: Task[Return])
                                  (implicit ec: scala.concurrent.ExecutionContext): Future[Return] = {
    val p = Promise[Return]()

    // Run the blocking sync on a daemon thread to avoid blocking the ScalaTest serial EC
    val runnable = new Runnable {
      override def run(): Unit = {
        try p.trySuccess(task.sync())
        catch {
          case th: Throwable => p.tryFailure(th)
        }
      }
    }
    val t = new Thread(runnable, "rapid-test-sync")
    t.setDaemon(true)
    t.start()

    // Fails the promise if it takes too long
    val timer = new java.util.Timer(true)
    val timerTask = new java.util.TimerTask {
      override def run(): Unit = p.tryFailure(new java.util.concurrent.TimeoutException("Async test timed out"))
    }
    timer.schedule(timerTask, testTimeout.toMillis)

    p.future.andThen {
      case _ =>
        timerTask.cancel()
        timer.cancel()
    }(ec)
  }

  implicit class TaskTestExtras[Return](task: Task[Return]) {
    def succeed: Task[Assertion] = task.map(_ => org.scalatest.Assertions.succeed)
  }
}