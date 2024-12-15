import scala.concurrent.duration.FiniteDuration
import scala.language.implicitConversions

package object rapid extends RapidPackage {
  implicit def fiber2Blockable[Return](fiber: Fiber[Return]): Blockable[Return] =
    fiber.asInstanceOf[Blockable[Return]]

  implicit class BlockableTask[Return](task: Task[Return]) extends Blockable[Return] {
    override def await(): Return = task
      .start()
      .asInstanceOf[Blockable[Return]]
      .await()

    override def await(duration: FiniteDuration): Option[Return] = task
      .start()
      .asInstanceOf[Blockable[Return]]
      .await(duration)
  }
}