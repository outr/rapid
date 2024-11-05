package rapid

import scala.concurrent.duration.FiniteDuration

trait Task[Return] extends Any {
  protected def f: () => Return

  def sync(): Return = f()
  def start(): Fiber[Return] = new Fiber(this)
  def await(): Return = start().await()
  def map[T](f: Return => T): Task[T] = Task(f(this.f()))
  def flatMap[T](f: Return => Task[T]): Task[T] = FlatMapTask(this, f)
  def sleep(duration: FiniteDuration): Task[Return] = flatMap { r =>
    Task.sleep(duration).map(_ => r)
  }
  def unit: Task[Unit] = map(_ => ())
}

class SimpleTask[Return](val f: () => Return) extends AnyVal with Task[Return]

case class FlatMapTask[Input, Return](input: Task[Input], task: Input => Task[Return]) extends Task[Return] {
  override protected def f: () => Return = () => {
    val i = input.sync()
    task(i).sync()
  }
}

object Task {
  lazy val unit: Task[Unit] = apply(())

  def apply[Return](f: => Return): Task[Return] = new SimpleTask(() => f)

  def sleep(duration: FiniteDuration): Task[Unit] = apply(Thread.sleep(duration.toMillis))
}