package rapid.v2

import scala.util.Try

case class Completable[Return](trace: Trace) extends Task[Return] {
  private var _result: Option[Try[Return]] = None

  def result: Option[Try[Return]] = _result

  def complete(result: Try[Return]): Unit = synchronized {
    _result = Some(result)
  }
}
