package rapid.task

import rapid.Task
import rapid.trace.Trace

import scala.util.{Failure, Success, Try}

case class Completable[+Return](trace: Trace) extends Task[Return] {
  @volatile private var _result: Option[Try[Any]] = None
  private var callbacks: List[Try[Any] => Unit] = Nil
  private val lock = new AnyRef

  def result: Option[Try[Return]] = _result.asInstanceOf[Option[Try[Return]]]

  def onComplete(cb: Try[Return] => Unit): Unit = lock.synchronized {
    val cba: Try[Any] => Unit = (r: Try[Any]) => cb(r.asInstanceOf[Try[Return]])
    _result match {
      case Some(r) => cba(r)
      case None => callbacks = cba :: callbacks
    }
  }

  def success[A >: Return](value: A): Unit = complete(Success(value))
  def failure(t: Throwable): Unit = complete(Failure(t))

  def complete[A >: Return](result: Try[A]): Unit = lock.synchronized {
    if (_result.isEmpty) {
      _result = Some(result.asInstanceOf[Try[Any]])
      val cbs = callbacks
      callbacks = Nil
      cbs.foreach { cb =>
        try cb(_result.get)
        catch { case _: Throwable => () }
      }
    }
  }
}
