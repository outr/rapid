package rapid

import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

class Fiber[Return](val task: Task[Return]) extends Task[Return] {
  private var result: Try[Return] = _

  private val thread = Thread.startVirtualThread(() => {
    result = Try(task.sync())
  })

  override protected def invoke(): Return = await()

  override def start(): Fiber[Return] = this

  override def await(): Return = {
    thread.join()
    result.get
  }

  override def attempt(): Try[Return] = {
    thread.join()
    result
  }

  def await(duration: Duration): Option[Return] = if (thread.join(java.time.Duration.ofMillis(duration.toMillis))) {
    Some(result.get)
  } else {
    None
  }
}

object Fiber {
  private val counter = new AtomicLong(0L)
}