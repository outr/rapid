package rapid

import java.util.concurrent.CancellationException
import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Try}

class VirtualThreadFiber[Return](val task: Task[Return]) extends Blockable[Return] with Fiber[Return] {
  @volatile private var result: Try[Return] = _
  @volatile private var cancelled = false

  private val thread = Thread.startVirtualThread(() => {
    if (!cancelled) {
      result = Try(task.sync())
    }
  })

  override protected def invoke(): Return = {
    thread.join()
    if (result == null && cancelled) {
      result = Failure(new CancellationException())
    }
    result.get
  }

  override def cancel(): Task[Boolean] = Task {
    if (!cancelled) {
      cancelled = true
      thread.interrupt()
      true
    } else {
      false
    }
  }

  override def await(duration: FiniteDuration): Option[Return] = if (thread.join(java.time.Duration.ofMillis(duration.toMillis))) {
    Option(result).flatMap(_.toOption)
  } else {
    None
  }
}