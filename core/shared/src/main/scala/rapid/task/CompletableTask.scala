package rapid.task

import rapid.Task

import scala.util.{Failure, Success, Try}

class CompletableTask[Return] extends Task[Return] {
  @volatile private var _result: Option[Try[Return]] = None
  @volatile private var _callbacks = List.empty[Try[Return] => Unit]

  def isComplete: Boolean = _result.nonEmpty

  def result: Option[Try[Return]] = _result

  def isSuccess: Boolean = _result.exists(_.isSuccess)
  def isFailure: Boolean = _result.exists(_.isFailure)

  def success(result: Return): Unit = synchronized {
    val success = Success(result)
    this._result = Some(success)
    _callbacks.foreach(_(success))
    notifyAll()
  }

  def failure(throwable: Throwable): Unit = synchronized {
    val failure = Failure(throwable)
    this._result = Some(failure)
    _callbacks.foreach(_(failure))
    notifyAll()
  }

  def onComplete(f: Try[Return] => Unit): Unit = synchronized {
    _callbacks = f :: _callbacks
  }

  def onSuccess(f: Return => Unit): Unit = onComplete {
    case Success(r) => f(r)
    case _ => // Ignore
  }

  def onFailure(f: Throwable => Unit): Unit = onComplete {
    case Failure(t) => f(t)
    case _ => // Ignore
  }

  override def sync(): Return = synchronized {
    while (_result.isEmpty) {
      wait()
    }
    _result.get.get
  }

  override def toString: String = "Completable"
}
