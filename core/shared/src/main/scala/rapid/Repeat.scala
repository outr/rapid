package rapid

trait Repeat[+Return] {
  protected def shouldRepeat[R >: Return](r: R): Boolean

  def apply[R >: Return](task: Task[R]): Task[R] = task.flatTap { r =>
    if (shouldRepeat(r)) {
      apply(task)
    } else {
      Task.pure(r)
    }
  }
}

object Repeat {
  case object Forever extends Repeat[Nothing] {
    override protected def shouldRepeat[R >: Nothing](r: R): Boolean = true
  }

  case class Times(count: Int) extends Repeat[Nothing] {
    @volatile private var counter = 0

    override protected def shouldRepeat[R >: Nothing](r: R): Boolean = {
      counter += 1
      counter < count
    }
  }

  case class Until[Return](predicate: Return => Boolean) extends Repeat[Return] {
    override protected def shouldRepeat[R >: Return](r: R): Boolean = {
      predicate(r.asInstanceOf[Return])
    }
  }
}