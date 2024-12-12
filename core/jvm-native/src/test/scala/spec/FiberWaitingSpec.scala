package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import rapid.Task

import scala.concurrent.duration.DurationInt

class FiberWaitingSpec extends AnyWordSpec with Matchers {
  "Fiber waiting" should {
    "await with a timeout" in {
      val task = Task {
        Thread.sleep(1000); 42
      }
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
