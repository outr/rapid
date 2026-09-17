package spec

import org.scalatest.concurrent.{Signaler, ThreadSignaler, TimeLimits}
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Seconds, Span}
import org.scalatest.wordspec.AnyWordSpec
import rapid._

/**
 * A fiber awaiting a result must release its carrier while it waits. The virtual-thread
 * scheduler caps its carrier pool (256 by default), so a wait that keeps the virtual
 * thread mounted lets a few hundred waiting fibers consume every carrier — and the
 * fibers that would produce the results they wait on can never be scheduled.
 */
class FiberCarrierStarvationSpec extends AnyWordSpec with Matchers with TimeLimits {
  private implicit val signaler: Signaler = ThreadSignaler

  private val waiterCount: Int = 1000

  "Fibers awaiting results" should {
    "complete when far more fibers wait than the carrier pool can hold" in {
      failAfter(Span(60, Seconds)) {
        val gate = Task.completable[Int]
        // Started first, and deliberately more than the carrier pool holds: each fiber
        // suspends on a result it cannot produce itself.
        val waiters = (0 until waiterCount).toList.map(_ => gate.map(_ + 1).start.sync())
        // Started last: if the waiters hold their carriers, this fiber's virtual thread
        // is queued behind them and never runs, so nothing ever completes.
        val producer = Task(gate.success(1)).start.sync()

        producer.sync()
        waiters.map(_.sync()) shouldBe List.fill(waiterCount)(2)
      }
    }

    "complete when each fiber joins a sibling fiber" in {
      failAfter(Span(60, Seconds)) {
        val gate = Task.completable[Int]
        val root = gate.start.sync()
        val siblings = (0 until waiterCount).toList.map(i => root.join.map(_ + i).start.sync())
        val producer = Task(gate.success(0)).start.sync()

        producer.sync()
        siblings.map(_.sync()) shouldBe (0 until waiterCount).toList
      }
    }
  }
}
