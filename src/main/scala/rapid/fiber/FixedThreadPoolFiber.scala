package rapid.fiber

import java.util.concurrent._
import scala.concurrent.duration._
import rapid.task.Task

class FixedThreadPoolFiber(threadCount: Int) {
  private val executor = Executors.newFixedThreadPool(threadCount)
  private val scheduler = Executors.newScheduledThreadPool(1)
  private val queue = new LinkedBlockingQueue[() => Unit]()

  // Start N workers
  (1 to threadCount).foreach { _ =>
    executor.execute(() => workerLoop())
  }

  private def workerLoop(): Unit = {
    while (true) {
      val task = queue.take()
      try task() catch {
        case t: Throwable => t.printStackTrace()
      }
    }
  }

  def run(task: Task[Unit]): Unit = {
    def step(t: Task[Unit]): Unit = t match {
      case Task.Sleep(duration, cont) =>
        scheduler.schedule(() => queue.offer(() => step(cont())), duration.toMillis, TimeUnit.MILLISECONDS)

      case Task.Complete =>
        () // do nothing
    }

    queue.offer(() => step(task))
  }
}
