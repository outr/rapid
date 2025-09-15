package rapid

import java.util.concurrent.{CancellationException, TimeUnit}
import java.util.concurrent.atomic.AtomicLong
import scala.util.{Failure, Success, Try}

class VirtualThreadFiber[Return](val task: Task[Return]) extends AbstractFiber[Return] {
  @volatile private var result: Try[Return] = _
  
  override val id: Long = VirtualThreadFiber.counter.incrementAndGet()

  private val thread = Thread
    .ofVirtual()
    .name(s"rapid-${id}")
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

  override protected def doSync(): Return = {
    thread.join()
    if (result == null && cancelled) {
      result = Failure(new CancellationException())
    }
    result.get
  }

  override protected def performCancellation(): Unit = {
    thread.interrupt()
  }

  override protected def doAwait(timeout: Long, unit: TimeUnit): Option[Return] = {
    val duration = java.time.Duration.ofMillis(unit.toMillis(timeout))
    if (thread.join(duration)) {
      Option(result) match {
        case Some(Success(value)) => Some(value)
        case Some(Failure(exception)) => throw exception
        case None => None
      }
    } else {
      None
    }
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