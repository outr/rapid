package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import rapid.Task

class FiberSpec extends AnyWordSpec with Matchers {
  "Fiber" should {
    "start and await a task" in {
      val task = Task { 5 * 5 }
      val fiber = task.start()
      fiber.await() shouldEqual 25
    }
    "handle task failures in fibers" in {
      val task = Task { throw new RuntimeException("Failure") }
      val fiber = task.start()
      an[RuntimeException] should be thrownBy fiber.await()
    }
  }
}