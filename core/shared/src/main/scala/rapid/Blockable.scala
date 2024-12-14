package rapid

import scala.concurrent.TimeoutException
import scala.concurrent.duration.FiniteDuration

trait Blockable[Return] {
  def await(): Return

  def await(duration: FiniteDuration): Option[Return]

  def timeout(duration: FiniteDuration): Task[Return] = Task {
    await(duration) match {
      case Some(value) => value
      case None => throw new TimeoutException(s"Task timed out after $duration")
    }
  }
}