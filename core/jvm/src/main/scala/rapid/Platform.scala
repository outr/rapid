package rapid

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration
import rapid.Task

object Platform {

  def fork[A](task: Task[A]): Unit = {
    val thread = new Thread(new Runnable {
      def run(): Unit = task.sync()
    }, s"rapid-fork-${System.nanoTime()}")

    thread.start()
  }

  def sleep(duration: FiniteDuration): Task[Unit] =
    Task {
      Thread.sleep(duration.toMillis)
    }

  // ✅ Required for FutureFiber.scala
  given executionContext: ExecutionContext = ExecutionContext.global
}
