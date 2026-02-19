package rapid

import java.util.concurrent.atomic.AtomicLong
import scala.collection.immutable.VectorMap

/**
 * Timer is used in Task to capture multi-execution timings. Very useful for finding bottlenecks in concurrent
 * applications.
 */
class Timer private(val name: Option[String]) {
  private[rapid] val _elapsed = new AtomicLong(0L)

  def elapsedNanos: Long = _elapsed.get()
  def elapsedMillis: Long = elapsedNanos / 1_000_000
  def elapsedSeconds: Double = elapsedMillis / 1000.0

  def effect[Return](f: => Return): Return = {
    val start = System.nanoTime()
    try {
      f
    } finally {
      _elapsed.addAndGet(System.nanoTime() - start)
    }
  }

  def reset(): Task[Unit] = Task(_elapsed.set(0L))
  def remove(): Boolean = name match {
    case Some(n) => Timer.remove(n).nonEmpty
    case None => false
  }
}

object Timer {
  private var map = VectorMap.empty[String, Timer]

  def apply(): Timer = new Timer(None)

  def apply(name: String): Timer = synchronized {
    val timer = new Timer(Some(name))
    map += name -> timer
    timer
  }

  def remove(name: String): Option[Timer] = synchronized {
    val existing = map.get(name)
    map -= name
    existing
  }

  def log: String = {
    val timers = map.map {
      case (name, timer) => s"\t$name: ${timer.elapsedSeconds} seconds"
    }.mkString("\n")
    s"Timer Stats:\n$timers"
  }
}