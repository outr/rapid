package rapid

import java.util.concurrent.atomic.AtomicLong
import scala.util.Try

trait Fiber[Return] extends Task[Return] {
  override def start(): Fiber[Return] = this

  override def await(): Return = invoke()
}

object Fiber {
  private val counter = new AtomicLong(0L)
}