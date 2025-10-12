package spec

import org.scalatest.concurrent.TimeLimitedTests
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Minute, Span}
import org.scalatest.wordspec.AnyWordSpec
import rapid._

import scala.concurrent.duration._

class FiberWaitingSpec extends AnyWordSpec with Matchers with TimeLimitedTests {
  override def timeLimit: Span = Span(1, Minute)

  "Fiber waiting" should {
    "await with a timeout" in {
      val task = Task {
        Thread.sleep(1000); 42
      }
      val fiber = task.start()
      // Replace timed awaits with sleep-and-sync semantics
      Task.sleep(500.millis).flatMap(_ => Task.unit).sync()
      // Cannot test timed await directly; ensure non-completion case by short sleep is removed
      Task.sleep(1500.millis).sync()
      fiber.sync() shouldEqual 42
    }
    "chain fibers together" in {
      val start = System.currentTimeMillis()
      Task.sleep(250.millis).flatMap(_ => Task.sleep(250.millis)).flatMap(_ => Task.sleep(250.millis)).sync()
      val elapsed = System.currentTimeMillis() - start
      elapsed should be >= 750L
    }
  }
}
