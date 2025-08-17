package rapid.task

import rapid.{Fiber, Task, TaskLike}
import scala.concurrent.duration.FiniteDuration

final class FiberTask[A](task: Task[A]) extends Fiber[A] {
  private lazy val result: A = task.sync()

  override def sync(): A = result

  override def await(): A = result

  def await(duration: FiniteDuration): Option[A] = Some(result)

  override def start: TaskLike[A] = new TaskLike[A] {
    override def sync(): A = FiberTask.this.sync()
    override def await(): A = FiberTask.this.await()
    override def start: TaskLike[A] = this
  }
}

object FiberTask {
  def apply[A](task: Task[A]): Fiber[A] = new FiberTask(task)
}
