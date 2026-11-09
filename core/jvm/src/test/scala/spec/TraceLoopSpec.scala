package spec

import org.scalatest.concurrent.TimeLimitedTests
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Minute, Span}
import org.scalatest.wordspec.AnyWordSpec
import rapid._
import rapid.trace.Trace

/**
 * A looping task must not cost memory per iteration.
 *
 * The interpreter records a pending frame by leaving its trace on the work stack, removed when that
 * frame's work finishes. `work.flatMap(_ => loop)` enters the next frame without finishing the last,
 * so before [[Trace.MaxFrames]] every iteration left one behind for the life of the fiber. A server
 * found 220,709,707 of them on 2026-10-26, 17.6GB of a 24GB heap, growing by 946 a second.
 *
 * Measured as heap held, because that is the symptom: the traces are all the same source line, so a
 * trace built from the stack collapses them into one entry and reads identically either way.
 */
class TraceLoopSpec extends AnyWordSpec with Matchers with TimeLimitedTests {
  override def timeLimit: Span = Span(1, Minute)

  private val Iterations = 1_000_000

  private def heldBytes(): Long = {
    System.gc()
    System.gc()
    val runtime = Runtime.getRuntime
    runtime.totalMemory() - runtime.freeMemory()
  }

  "A looping task" should {
    "hold no more memory halfway through a million iterations than at the start" in {
      // The leak only exists with tracing on, so a run with it off would prove nothing.
      Trace.Enabled should be(true)

      @volatile var midway: Long = 0L
      val half = Iterations / 2

      def loop(remaining: Int): Task[Int] = if (remaining == 0) {
        Task.pure(0)
      } else {
        Task {
          // Measured INSIDE the loop: the stack is what holds the traces, so once the fiber finishes
          // they are garbage and nothing is left to find.
          if (remaining == half) midway = heldBytes()
          remaining - 1
        }.flatMap(loop)
      }

      val start = heldBytes()
      loop(Iterations).sync() should be(0)

      midway should be > 0L
      // A trace is 80 bytes with its three sourcecode wrappers, so one per iteration is ~40MB by the
      // halfway point and ~80MB by the end. MaxFrames holds it to 64 of them.
      (midway - start) should be < 16L * 1024 * 1024
    }
  }
}
