package rapid

sealed trait Opt[+A] extends Any {
  def isEmpty: Boolean
  def isNonEmpty: Boolean = !isEmpty
  def foreach(f: A => Unit): Unit = ()
}

object Opt {
  case class Value[+A](value: A) extends AnyVal with Opt[A] {
    override def isEmpty: Boolean = false

    override def foreach(f: A => Unit): Unit = f(value)
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