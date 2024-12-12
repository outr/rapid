package spec

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import rapid.{Fiber, Task}
import scala.concurrent.duration._

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
    "await with a timeout" in {
      val task = Task { Thread.sleep(1000); 42 }
      val fiber = task.start()
      fiber.await(500.millis) shouldEqual None
      fiber.await(1500.millis) shouldEqual Some(42)
    }
    "chain fibers together" in {
      val start = System.currentTimeMillis()
      Task.sleep(250.millis).start().flatMap { _ =>
        Task.sleep(250.millis).start().flatMap { _ =>
          Task.sleep(250.millis).start()
        }
      }.sync()
      val elapsed = System.currentTimeMillis() - start
      elapsed should be >= 750L
    }
  }
}