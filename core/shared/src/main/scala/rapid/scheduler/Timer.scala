package rapid.scheduler

import scala.concurrent.duration._
import java.util.concurrent.atomic.AtomicBoolean

trait CancelToken { 
  def cancel(): Unit 
}

trait Timer {
  /** Schedule a callback after delay; MUST NOT block a worker thread. */
  def schedule(delay: FiniteDuration)(k: Runnable): CancelToken
  def shutdown(): Unit
}