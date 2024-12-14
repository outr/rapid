import scala.language.implicitConversions

package object rapid extends RapidPackage {
  implicit def fiber2Blockable[Return](fiber: Fiber[Return]): Blockable[Return] =
    fiber.asInstanceOf[Blockable[Return]]
}