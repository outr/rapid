package rapid

import scala.concurrent.ExecutionContext

/**
 * Platform abstraction for execution context, fiber creation, and scheduling.
 * Each platform (JVM, JS, Native) provides its own implementation.
 */
trait RapidPlatform {
  def executionContext: ExecutionContext
  def supportsCancel: Boolean
  def createFiber[Return](task: Task[Return]): Fiber[Return]
  def delay(millis: Long): Unit
  def runAsync(task: Task[_]): Unit
  /** Hint to yield to other threads/tasks; no-op on Scala.js. */
  def yieldNow(): Unit
  /** Default random number generator for Unique etc.: max exclusive upper bound, returns 0 until max-1. */
  def defaultRandom: Forge[Int, Int]
  /** Compile and run a ParallelStream. JVM uses a dedicated thread pool; JS/Native fall back to sequential. */
  def compileParallelStream[T, R](stream: ParallelStream[T, R], handle: R => Unit, complete: Int => Unit, onError: Throwable => Unit): Unit
  /** Schedule a thunk to run on the platform's scheduler (non-blocking). */
  def schedule(thunk: () => Unit): Unit
  /** Schedule a thunk to run after a delay in milliseconds. */
  def scheduleDelay(millis: Long)(thunk: () => Unit): Unit
  /** Block until the fiber completes and return the result. On JS, throws if the fiber hasn't completed. */
  def awaitFiber[R](fiber: Fiber[R]): R
}
