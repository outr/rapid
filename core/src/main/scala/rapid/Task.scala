package rapid

import scala.concurrent.duration.FiniteDuration

class Task[Return](val f: () => Return) extends AnyVal {
  def sync(): Return = f()
  def start(): Fiber[Return] = new Fiber(this)
  def await(): Return = start().await()
  def map[T](f: Return => T): Task[T] = Task(f(this.f()))
  def flatMap[T](f: Return => Task[T]): Task[T] = Task {
    f(this.f()).sync()
  }
  def sleep(duration: FiniteDuration): Task[Return] = flatMap { r =>
    Task.sleep(duration).map(_ => r)
  }
  def unit: Task[Unit] = map(_ => ())
}

object Task {
  lazy val unit: Task[Unit] = apply(())

  def apply[Return](f: => Return): Task[Return] = new Task(() => f)

  def sleep(duration: FiniteDuration): Task[Unit] = apply(Thread.sleep(duration.toMillis))
}