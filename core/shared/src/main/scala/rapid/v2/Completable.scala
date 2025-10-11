package rapid.v2

import scala.util.{Failure, Success, Try}

case class Completable[Return](trace: Trace) extends Task[Return] {
  @volatile private var _result: Option[Try[Return]] = None
  private var callbacks: List[Try[Return] => Unit] = Nil

  def result: Option[Try[Return]] = _result

  def onComplete(cb: Try[Return] => Unit): Unit = synchronized {
    _result match {
      case Some(r) => cb(r)
      case None => callbacks = cb :: callbacks
    }
  }

  def success(value: Return): Unit = complete(Success(value))
  def failure(t: Throwable): Unit = complete(Failure(t))

  def complete(result: Try[Return]): Unit = synchronized {
    if (_result.isEmpty) {
      _result = Some(result)
      val cbs = callbacks
      callbacks = Nil
      notifyAll()
      cbs.foreach { cb =>
        try cb(result)
        catch { case _: Throwable => () }
      }
    }
  }

  // Fallback blocking wait for synchronous execution mode
  override def sync(): Return = synchronized {
    while (_result.isEmpty) wait()
    _result.get match {
      case Success(r) => r
      case Failure(t) => throw t
    }
  }
}
