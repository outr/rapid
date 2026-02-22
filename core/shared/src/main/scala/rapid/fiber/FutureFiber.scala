package rapid.fiber

import rapid.{Fiber, Task}

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success, Try}

class FutureFiber[Return](task: Task[Return])(implicit ec: ExecutionContext) extends Fiber[Return] {
  private val inner: SynchronousFiber[Return] = SynchronousFiber(task)

  override def sync(): Return = inner.sync()

  override def join: Task[Return] = inner.join

  override def onComplete(f: Try[Return] => Unit): Unit = inner.onComplete(f)
}
