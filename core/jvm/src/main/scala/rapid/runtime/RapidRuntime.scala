package rapid.runtime

import rapid.scheduler.{HashedWheelTimer2, Timer2}
import rapid.FixedThreadPoolFiber

/** Holds global runtime singletons used by the fixed-pool fiber. */
object RapidRuntime {
  // ---- Test-tunable parameters via system properties ----
  private def intProp(name: String, default: Int): Int =
    sys.props.get(name).flatMap(s => util.Try(s.toInt).toOption).getOrElse(default)

  // Defaults remain your P3 throughput choices; tests can override with -D...
  private val capPow2   = intProp("rapid.ready.capacityPow2", 22)   // e.g. -Drapid.ready.capacityPow2=20
  private val tickMs    = intProp("rapid.timer.tick",         5)    // e.g. -Drapid.timer.tick=1
  private val wheelSize = intProp("rapid.timer.wheel",        2048) // e.g. -Drapid.timer.wheel=512
  private val drainBat  = intProp("rapid.timer.drainBatch",   4096) // e.g. -Drapid.timer.drainBatch=1024

  // Ready queue for wakeups (fiber, cont)
  val readyQueue: ReadyQueue = new ReadyQueue(1 << capPow2)

  // Fast timer (coarse by default; can be made fine in tests)
  val timer2: Timer2 = new HashedWheelTimer2(
    tickMillis = tickMs,
    wheelSize  = wheelSize,
    ready      = readyQueue,
    pool       = FixedThreadPoolFiber.executor,
    drainBatch = drainBat
  )
}