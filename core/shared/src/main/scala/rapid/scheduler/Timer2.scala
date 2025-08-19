package rapid.scheduler

import rapid.Task

trait Timer2 {
  /** Register a sleep for the given deadline (System.nanoTime domain). */
  def registerSleep(deadlineNanos: Long, fiber: AnyRef, cont: AnyRef /* continuation or null */): CancelToken
  def nowNanos(): Long
  def shutdown(): Unit
}