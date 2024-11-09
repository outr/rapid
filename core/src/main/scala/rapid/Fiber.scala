package rapid

import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.duration._

class Fiber[Return](val task: Task[Return]) extends Task[Return] {
  private var result: Either[Throwable, Return] = _
  private val thread = Thread
    .ofVirtual()
    .name(s"rapid-${Fiber.counter.incrementAndGet()}")
    .start(() => try {
      result = Right(task.sync())
    } catch {
      case t: Throwable => result = Left(t)
    })

  override protected lazy val f: () => Return = () => await()

  override def start(): Fiber[Return] = this

  override def await(): Return = {
    thread.join()
    result match {
      case Left(t) => throw t
      case Right(r) => r
    }
  }

  override def attempt(): Either[Throwable, Return] = {
    thread.join()
    result
  }

  def await(duration: Duration): Option[Return] = if (thread.join(java.time.Duration.ofMillis(duration.toMillis))) {
    result match {
      case Left(t) => throw t
      case Right(r) => Some(r)
    }
  } else {
    None
  }
}

object Fiber {
  private val counter = new AtomicLong(0L)
}