package rapid

/**
 * Represents a pull-based computation that produces a result of type `A`.
 *
 * @tparam Return the type of the result produced by this pull
 */
trait Pull[Return] {
  def flatMap[B](f: Return => Pull[B]): Pull[B]
  def map[B](f: Return => B): Pull[B]

  /**
   * Converts the pull to a task.
   *
   * @return a task representing the pull
   */
  def toTask: Task[Return] = this match {
    case Pull.Pure(value) => Task(value)
    case Pull.Suspend(resume) => Task.defer(resume().toTask)
  }
}

object Pull {
  /**
   * Represents a pull that produces a value.
   *
   * @param value the value produced by the pull
   * @tparam Return the type of the value
   */
  case class Pure[Return](value: Return) extends Pull[Return] {
    def flatMap[T](f: Return => Pull[T]): Pull[T] = f(value)
    def map[T](f: Return => T): Pull[T] = Pure(f(value))
  }

  /**
   * Represents a suspended pull that can be resumed to produce a value.
   *
   * @param resume the function to resume the pull
   * @tparam Return the type of the value produced by the pull
   */
  case class Suspend[Return](resume: () => Pull[Return]) extends Pull[Return] {
    def flatMap[T](f: Return => Pull[T]): Pull[T] = Suspend(() => resume().flatMap(f))
    def map[T](f: Return => T): Pull[T] = Suspend(() => resume().map(f))
  }

  /**
   * Creates a new pull that produces the given value.
   *
   * @param value the value to produce
   * @tparam Return the type of the value
   * @return a new pull that produces the given value
   */
  def pure[Return](value: Return): Pull[Return] = Pure(value)

  /**
   * Creates a new suspended pull that can be resumed to produce a value.
   *
   * @param resume the function to resume the pull
   * @tparam A the type of the value produced by the pull
   * @return a new suspended pull
   */
  def suspend[A](resume: => Pull[A]): Pull[A] = Suspend(() => resume)
}