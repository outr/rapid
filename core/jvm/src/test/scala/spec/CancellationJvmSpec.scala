package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import rapid._

import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.duration._

/**
 * Cancellation / interruption (JVM virtual-thread fibers). `Fiber.cancel`
 * interrupts the carrier thread, so a blocked fiber aborts promptly instead of
 * running its work to completion; `Task.race` / `Task.timeout` build on it.
 */
class CancellationJvmSpec extends AnyWordSpec with Matchers {

  "Fiber.cancel" should {
    "interrupt an in-flight blocking task so the fiber fails fast" in {
      val started = System.currentTimeMillis()
      val fiber = Task { Thread.sleep(30000L); 42 }.start.sync()
      Thread.sleep(150L) // let it get into the sleep
      fiber.cancel.sync() shouldBe true
      val outcome = fiber.join.attempt.sync()
      withClue(s"outcome=$outcome: ") { outcome.isFailure shouldBe true }
      val elapsed = System.currentTimeMillis() - started
      withClue(s"elapsed=${elapsed}ms (should be ~150ms, not 30s): ") {
        elapsed should be < 5000L
      }
    }

    "return false when cancelling an already-completed fiber" in {
      val fiber = Task { 21 * 2 }.start.sync()
      fiber.join.sync() shouldBe 42
      fiber.cancel.sync() shouldBe false
    }
  }

  "Task.timeout" should {
    "fail a too-slow task with a TimeoutException, promptly" in {
      val started = System.currentTimeMillis()
      val outcome = Task { Thread.sleep(30000L); 42 }.timeout(200.millis).attempt.sync()
      withClue(s"outcome=$outcome: ") {
        outcome.isFailure shouldBe true
        outcome.failed.get shouldBe a[TimeoutException]
      }
      val elapsed = System.currentTimeMillis() - started
      withClue(s"elapsed=${elapsed}ms (should be ~200ms, not 30s): ") {
        elapsed should be < 5000L
      }
    }

    "return the value when the task finishes within the limit" in {
      Task { 21 * 2 }.timeout(5.seconds).sync() shouldBe 42
    }
  }

  "Task.race" should {
    "return Left when the first task wins, cancelling the loser" in {
      val loserRan = new AtomicBoolean(false)
      val fast = Task { 1 }
      val slow = Task { Thread.sleep(30000L); loserRan.set(true); 2 }
      Task.race(fast, slow).sync() shouldBe Left(1)
      // The loser was cancelled before it could finish.
      Thread.sleep(200L)
      loserRan.get() shouldBe false
    }

    "return Right when the second task wins" in {
      val slow = Task { Thread.sleep(30000L); 1 }
      val fast = Task { 2 }
      Task.race(slow, fast).sync() shouldBe Right(2)
    }
  }
}
