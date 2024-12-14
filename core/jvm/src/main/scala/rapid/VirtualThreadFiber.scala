package rapid

import scala.concurrent.duration.FiniteDuration
import scala.util.Try

class VirtualThreadFiber[Return](val task: Task[Return]) extends Blockable[Return] with Fiber[Return] {
  private var result: Try[Return] = _

  private val thread = Thread.startVirtualThread(() => {
    result = Try(task.sync())
  })

  override protected def invoke(): Return = {
    thread.join()
    result.get
  }

  override def await(duration: FiniteDuration): Option[Return] = if (thread.join(java.time.Duration.ofMillis(duration.toMillis))) {
    Some(result.get)
  } else {
    None
  }
}