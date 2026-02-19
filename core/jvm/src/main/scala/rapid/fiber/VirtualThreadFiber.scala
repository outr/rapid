package rapid.fiber

import rapid.{Fiber, Task}
import rapid.task.{Completable, Pure}

import scala.util.{Failure, Success, Try}

class VirtualThreadFiber[Return](task: Task[Return]) extends Fiber[Return] {
  @volatile private var _result: Option[Try[Return]] = None
  private var completionCallbacks: List[Try[Return] => Unit] = Nil
  private val lock = new AnyRef

  private val thread = Thread
    .ofVirtual()
    .start(() => {
      val r = Try(SynchronousFiber(task).awaitBlocking())
      completeWith(r)
    })

  private def completeWith(result: Try[Return]): Unit = lock.synchronized {
    _result = Some(result)
    val cbs = completionCallbacks
    completionCallbacks = Nil
    lock.notifyAll()
    cbs.foreach { cb =>
      try cb(result)
      catch { case _: Throwable => () }
    }
  }

  override def sync(): Return = lock.synchronized {
    while (_result.isEmpty) lock.wait()
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

  override def onComplete(f: Try[Return] => Unit): Unit = lock.synchronized {
    _result match {
      case Some(r) => f(r)
      case None => completionCallbacks = f :: completionCallbacks
    }
  }
}
