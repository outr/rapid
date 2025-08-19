package rapid.task

import rapid.Task
import rapid.scheduler.CancelToken

/** Non-blocking suspension: register a callback, return immediately. */
final case class AsyncTask[A](register: (Either[Throwable, A] => Unit) => CancelToken) extends Task[A] {
  override def toString: String = "Async"
}