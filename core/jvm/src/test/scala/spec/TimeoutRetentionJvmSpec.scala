package spec

import org.scalatest.concurrent.TimeLimitedTests
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Minute, Span}
import org.scalatest.wordspec.AnyWordSpec
import rapid._

import java.lang.ref.WeakReference
import scala.concurrent.duration._

/**
 * A task that wins its timeout leaves nothing of itself behind: the timer it beat is cancelled, so the result is not
 * kept alive until the timeout would have fired. On fibers run by the thread pool (every fiber where the runtime has no
 * virtual threads, as on Android) a fiber waiting out a sleep has no thread to interrupt, and cancelling it used to
 * leave its timer queued, holding the race and the winner's result, for the whole timeout.
 */
class TimeoutRetentionJvmSpec extends AnyWordSpec with Matchers with TimeLimitedTests {
  override def timeLimit: Span = Span(1, Minute)

  /** Runs `f` with fibers on the thread pool, as a runtime without virtual threads runs them. */
  private def onThreadPool[T](f: => T): T = {
    val before = Task.Virtual
    Task.Virtual = false
    try f finally Task.Virtual = before
  }

  /** Whether every one of `refs` is collected within a few rounds of garbage collection. */
  private def allCollected(refs: Seq[WeakReference[Array[Byte]]]): Boolean =
    (1 to 20).exists { _ =>
      System.gc()
      Thread.sleep(50L)
      refs.forall(_.get() eq null)
    }

  "Task.timeout" should {
    "hold none of the results of tasks that finished in time, on thread-pool fibers" in onThreadPool {
      val refs = (1 to 1000).map { _ =>
        val big = new Array[Byte](64 * 1024)
        val ref = new WeakReference(big)
        Task.pure(big).timeout(1.hour).sync()
        ref
      }
      val collected = allCollected(refs)
      withClue(s"${refs.count(_.get() ne null)} of ${refs.size} results still held: ")(collected shouldBe true)
    }
  }

  "Fiber.cancel" should {
    "end a thread-pool fiber that is waiting out a sleep, promptly, as a failure" in onThreadPool {
      val started = System.currentTimeMillis()
      val fiber = Task.sleep(1.hour).map(_ => 42).start.sync()
      Thread.sleep(100L)
      fiber.cancel.sync() shouldBe true
      fiber.join.attempt.sync().isFailure shouldBe true
      (System.currentTimeMillis() - started) should be < 5000L
    }
  }
}
