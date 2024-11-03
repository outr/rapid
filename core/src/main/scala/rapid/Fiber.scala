package rapid

import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.duration._

class Fiber[Return](val task: Task[Return]) {
  private var result: Return = _
  private val thread = Thread.ofVirtual().name(s"rapid-${Fiber.counter.incrementAndGet()}").start(() => result = task())

  def await(): Return = {
    thread.join()
    result
  }

  def await(duration: Duration): Option[Return] = if (thread.join(java.time.Duration.ofMillis(duration.toMillis))) {
    Some(result)
  } else {
    None
  }
}

object Fiber {
  private val counter = new AtomicLong(0L)
}