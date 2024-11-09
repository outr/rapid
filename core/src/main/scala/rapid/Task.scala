package rapid

import scala.concurrent.duration.FiniteDuration

trait Task[Return] extends Any {
  protected def f: () => Return

  def sync(): Return = f()
  def start(): Fiber[Return] = new Fiber(this)
  def await(): Return = start().await()
  def attempt(): Either[Throwable, Return] = start().attempt()
  def map[T](f: Return => T): Task[T] = Task(f(this.f()))
  def flatMap[T](f: Return => Task[T]): Task[T] = ChainedTask(List(
    v => f(v.asInstanceOf[Return]).asInstanceOf[Task[Any]],
    (_: Any) => this.asInstanceOf[Task[Any]],
  ))
  def sleep(duration: FiniteDuration): Task[Return] = flatMap { r =>
    Task.sleep(duration).map(_ => r)
  }
  def unit: Task[Unit] = map(_ => ())

  def toPull: Pull[Return] = Pull.suspend(Pull.output(sync()))
}

object Task {
  lazy val unit: Task[Unit] = apply(())

  def apply[Return](f: => Return): Task[Return] = new SimpleTask(() => f)

  def sleep(duration: FiniteDuration): Task[Unit] = apply(Thread.sleep(duration.toMillis))

  def defer[Return](task: => Task[Return]): Task[Return] = Task(task.sync())
}