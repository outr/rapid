package rapid

import scala.util.Try

trait Fiber[+Return] extends Any {
  def sync(): Return
  def join: Task[Return]
  def onComplete(f: Try[Return] => Unit): Unit
}
