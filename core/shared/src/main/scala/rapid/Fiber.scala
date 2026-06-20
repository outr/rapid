package rapid

import scala.util.Try

trait Fiber[+Return] extends Any {
  def sync(): Return
  def join: Task[Return]
  def onComplete(f: Try[Return] => Unit): Unit

  /**
   * Best-effort cancellation of the running fiber.
   *
   * Interrupts the thread executing this fiber, so an interruptible blocking
   * operation — `Thread.sleep`, `Object.wait` / a parked await (e.g. waiting on
   * a [[rapid.task.Completable]] such as an async HTTP call), NIO channel I/O,
   * or any code that checks `Thread.interrupted()` — aborts promptly. The fiber
   * then completes with the resulting `InterruptedException`, so a `join` /
   * `sync` returns right away instead of blocking until the work finishes.
   *
   * Returns a Task yielding `true` if a cancel signal was delivered (the fiber
   * was still running on an interruptible thread), `false` if it had already
   * completed or the platform can't interrupt (Scala.js, an already-finished
   * fiber). NOT a hard kill: a thread spinning on CPU with no checks, or blocked
   * in non-interruptible classic socket I/O, keeps running until it next reaches
   * an interruptible point. Work that was suspended onto an external callback
   * (a Completable completed elsewhere) is abandoned — the fiber stops waiting,
   * but the external operation continues unless its own API is cancelled.
   *
   * The default is a no-op (`false`) for fiber kinds that have no interruptible
   * carrier thread; JVM async fibers override it.
   */
  def cancel: Task[Boolean] = Task.pure(false)
}
