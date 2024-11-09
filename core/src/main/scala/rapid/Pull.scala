package rapid

trait Pull[A] {
  def flatMap[B](f: A => Pull[B]): Pull[B]
  def map[B](f: A => B): Pull[B]
  def toTask: Task[A] = this match {
    case Pull.Output(value) => Task(value)
    case Pull.Suspend(resume) => Task.defer(resume().toTask)
  }
}

object Pull {
  case class Output[A](value: A) extends Pull[A] {
    def flatMap[B](f: A => Pull[B]): Pull[B] = f(value)
    def map[B](f: A => B): Pull[B] = Output(f(value))
  }

  case class Suspend[A](resume: () => Pull[A]) extends Pull[A] {
    def flatMap[B](f: A => Pull[B]): Pull[B] = Suspend(() => resume().flatMap(f))
    def map[B](f: A => B): Pull[B] = Suspend(() => resume().map(f))
  }

  def output[A](value: A): Pull[A] = Output(value)
  def suspend[A](resume: => Pull[A]): Pull[A] = Suspend(() => resume)
}