package rapid

sealed trait Step[+T]

object Step {
  case class Emit[T](value: T) extends Step[T]
  case class Concat[T](pull: Pull[T]) extends Step[T]
  case object Skip extends Step[Nothing]
  case object Stop extends Step[Nothing]
}