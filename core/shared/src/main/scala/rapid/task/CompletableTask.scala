package rapid.task

import rapid.Task

import scala.util.{Failure, Success, Try}

class CompletableTask[Return] extends Task[Return] {
  @volatile private var _result: Option[Try[Return]] = None
  @volatile private var _callbacks = List.empty[Return => Unit]
  @volatile private var _errorCallbacks = List.empty[Throwable => Unit]

  def isComplete: Boolean = _result.nonEmpty

  def result: Option[Try[Return]] = _result

  def isSuccess: Boolean = _result.exists(_.isSuccess)
  def isFailure: Boolean = _result.exists(_.isFailure)

  def success(result: Return): Unit = synchronized {
    this._result = Some(Success(result))
    _callbacks.foreach(_(result))
    notifyAll()
  }

  def failure(throwable: Throwable): Unit = synchronized {
    this._result = Some(Failure(throwable))
    _errorCallbacks.foreach(_(throwable))
    notifyAll()
  }

  def onSuccess(f: Return => Unit): Unit = synchronized {
    _callbacks = f :: _callbacks
  }

  def onFailure(f: Throwable => Unit): Unit = synchronized {
    _errorCallbacks = f :: _errorCallbacks
  }

  override def sync(): Return = synchronized {
    while (_result.isEmpty) {
      wait()
    }
    _result.get.get
  }

  override def toString: String = "Completable"
}
