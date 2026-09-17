package rapid.fiber

import java.util.concurrent.locks.{Condition, ReentrantLock}

/**
 * Mutual exclusion plus the signal a fiber's result handoff waits on.
 *
 * A ReentrantLock/Condition pair rather than an intrinsic monitor: on JDK < 24 a virtual
 * thread that waits inside `synchronized` stays mounted on its carrier. The default
 * scheduler holds at most 256 carriers, so a few hundred fibers awaiting results that
 * other fibers must produce consume every carrier and the producers can never be
 * scheduled. Awaiting a Condition unmounts the waiter and frees the carrier.
 */
private[rapid] final class CompletionSignal {
  private val lock = new ReentrantLock()
  private val completed: Condition = lock.newCondition()

  def acquire(): Unit = lock.lock()

  def release(): Unit = lock.unlock()

  /** Waits for `signalAll`. The caller must hold this signal. */
  def await(): Unit = completed.await()

  /** Wakes every waiter. The caller must hold this signal. */
  def signalAll(): Unit = completed.signalAll()
}
