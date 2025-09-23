package rapid

import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success, Try}

class VirtualThreadFiber[Return](val task: Task[Return]) extends Blockable[Return] with Fiber[Return] {
  override val id: Long = VirtualThreadFiber.counter.incrementAndGet()
  @volatile private var result: Try[Return] = _
  @volatile private var cancelled = false

  private val thread = Thread
    .ofVirtual()
    .name(s"rapid-$id")
    .start(() => {
      if (!cancelled) {
        try {
          result = Try(task.sync())
        } catch {
          case _: InterruptedException if cancelled => result = Failure(new CancellationException("Task was cancelled"))
          case t: Throwable => result = Failure(t)
        }
      }
    })

  override def sync(): Return = {
    thread.join()
    if (result == null && cancelled) {
      result = Failure(new CancellationException())
    }
    result.get
  }

  override def cancel: Task[Boolean] = Task {
    if (!cancelled) {
      cancelled = true
      thread.interrupt()
      true
    } else {
      false
    }
  }

  override def await(duration: FiniteDuration): Option[Return] = if (thread.join(java.time.Duration.ofMillis(duration.toMillis))) {
    Option(result) match {
      case Some(Success(value)) => Some(value)
      case Some(Failure(exception)) => throw exception
      case None => None
    }
  } else {
    None
  }
}

object VirtualThreadFiber {
  private val counter = new AtomicLong(0L)

  def fireAndForget(task: Task[_]): Unit = {
    Thread
      .ofVirtual()
      .name(s"rapid-vt-${counter.incrementAndGet()}")
      .start(() => {
        task.sync()
      })
  }
}