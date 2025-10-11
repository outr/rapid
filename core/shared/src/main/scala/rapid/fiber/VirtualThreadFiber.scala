package rapid.fiber

import rapid.{Fiber, Task}

import scala.util.Try

case class VirtualThreadFiber[Return](task: Task[Return]) extends Fiber[Return] {
  @volatile private var result: Option[Try[Return]] = None

  private val thread = Thread
    .ofVirtual()
    .start(() => {
      result = Some(Try(SynchronousFiber(task).sync()))
    })

  override def sync(): Return = {
    thread.join()
    result.get.get
  }
}
