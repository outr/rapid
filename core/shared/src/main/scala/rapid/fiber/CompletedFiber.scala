package rapid.fiber

import rapid.Fiber

final case class CompletedFiber[+Return](value: Return) extends Fiber[Return] {
  override def sync(): Return = value
}