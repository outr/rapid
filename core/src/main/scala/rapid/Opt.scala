package rapid

sealed trait Opt[+A] extends Any

object Opt {
  case class Value[+A](value: A) extends AnyVal with Opt[A]

  case object Empty extends Opt[Nothing]
}