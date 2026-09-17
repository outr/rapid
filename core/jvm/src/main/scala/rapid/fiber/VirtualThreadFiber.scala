package rapid.fiber

import rapid.{Fiber, Task}
import rapid.task.{Completable, Pure}

import scala.util.{Failure, Success, Try}

class VirtualThreadFiber[Return](task: Task[Return]) extends Fiber[Return] {
  @volatile private var _result: Option[Try[Return]] = None
  private var completionCallbacks: List[Try[Return] => Unit] = Nil
  // sync() parks here until the fiber completes. See CompletionSignal for why this
  // is not an intrinsic monitor.
  private val lock = new CompletionSignal

  private val thread = Thread
    .ofVirtual()
    .start(() => {
      try {
        completeWith(Success(SynchronousFiber(task).awaitBlocking()))
      } catch {
        case t: Throwable => completeWith(Failure(t))
      }
    })

  /** Interrupt the virtual thread carrying this fiber. The thread is either
    * running the work (interrupting an in-flight blocking op) or parked in
    * `awaitBlocking()` waiting on the result (interrupting throws from the await),
    * so cancel unblocks the fiber promptly in both states. No-op if already
    * complete. */
  override def cancel: Task[Boolean] = Task {
    if (_result.isEmpty) {
      thread.interrupt()
      true
    } else false
  }

  private def completeWith(result: Try[Return]): Unit = {
    var cbs: List[Try[Return] => Unit] = Nil
    lock.acquire()
    try {
      _result = Some(result)
      cbs = completionCallbacks
      completionCallbacks = Nil
      lock.signalAll()
    } finally {
      lock.release()
    }
    cbs.foreach { cb =>
      try cb(result)
      catch { case _: Throwable => () }
    }
  }

  override def sync(): Return = {
    lock.acquire()
    try {
      while (_result.isEmpty) lock.await()
    } finally {
      lock.release()
    }
    _result.get.get
  }

  override def join: Task[Return] = {
    val c = Task.completable[Return]
    onComplete {
      case Success(v) => c.success(v)
      case Failure(t) => c.failure(t)
    }
    c
  }

  override def onComplete(f: Try[Return] => Unit): Unit = {
    var immediate: Option[Try[Return]] = None
    lock.acquire()
    try {
      _result match {
        case Some(r) => immediate = Some(r)
        case None => completionCallbacks = f :: completionCallbacks
      }
    } finally {
      lock.release()
    }
    immediate.foreach(f)
  }
}
