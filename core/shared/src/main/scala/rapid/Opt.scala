package rapid

sealed trait Opt[+A] extends Any {
  def isEmpty: Boolean
  def notEmpty: Boolean = !isEmpty
  def foreach(f: A => Unit): Unit = ()
  def map[R](f: A => R): Opt[R] = Opt.Empty
  def orElse[B >: A](f: => B): B = f
}

object Opt {
  case class Value[+A](value: A) extends AnyVal with Opt[A] {
    override def isEmpty: Boolean = false
    override def foreach(f: A => Unit): Unit = f(value)
    override def map[R](f: A => R): Opt[R] = Value(f(value))
    override def orElse[B >: A](f: => B): B = value
  }
  case object Empty extends Opt[Nothing] {
    override def isEmpty: Boolean = true
  }

  def apply[A](value: A): Opt[A] = if (value == null) {
    Empty
  } else {
    Value(value)
  }
}