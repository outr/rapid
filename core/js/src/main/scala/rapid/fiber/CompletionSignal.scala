package rapid.fiber

/**
 * Mutual exclusion plus the signal a fiber's result handoff waits on.
 *
 * Scala.js runs single-threaded: there is nothing to exclude and no thread that could
 * be woken, so only `await` has anything to report.
 */
private[rapid] final class CompletionSignal {
  def acquire(): Unit = ()

  def release(): Unit = ()

  def await(): Unit = throw new UnsupportedOperationException(
    "Cannot block awaiting a fiber on Scala.js. Use .toFuture or .runAsync instead."
  )

  def signalAll(): Unit = ()
}
