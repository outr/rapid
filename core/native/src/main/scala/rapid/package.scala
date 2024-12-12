import scala.language.implicitConversions

package object rapid extends RapidPackage {
  implicit def fiber2Blockable[Return](fiber: Fiber[Return]): BlockableFiber[Return] =
    fiber.asInstanceOf[BlockableFiber[Return]]
}