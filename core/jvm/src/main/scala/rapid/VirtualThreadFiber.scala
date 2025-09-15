package rapid

import java.util.concurrent.{CancellationException, TimeUnit}
import java.util.concurrent.atomic.AtomicLong
import scala.util.{Failure, Success, Try}

class VirtualThreadFiber[Return](val task: Task[Return]) extends AbstractFiber[Return] {
  @volatile private var result: Try[Return] = _
  
  // Use thread-local ID generator to eliminate CAS contention (same as FixedThreadPoolFiber)
  override val id: Long = FiberIdGenerator.nextId()

  private val thread = Thread
    .ofVirtual()
    .name(s"rapid-${id}")
    .start(() => {
      if (!cancelled) {
        // Use the shared optimized execution engine
        SharedExecutionEngine.executeCallback(
          task,
          value => result = Success(value),
          error => result = Failure(error),
          None  // VirtualThread doesn't need an executor - it IS the thread
        )
      } else {
        result = Failure(new CancellationException("Task was cancelled"))
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
  // Removed - now using FiberIdGenerator instead

  def fireAndForget(task: Task[_]): Unit = {
    Thread
      .ofVirtual()
      .name(s"rapid-vt-${FiberIdGenerator.nextId()}")
      .start(() => {
        // Use the shared optimized execution engine
        SharedExecutionEngine.executeCallback(task, _ => (), _ => (), None)
      })
  }
}