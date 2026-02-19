package rapid

import scala.concurrent.duration.FiniteDuration

/**
 * Abstraction for a running task that can be awaited, optionally with a timeout.
 * Used on the JVM and Scala Native where blocking await is supported.
 */
trait Blockable[+Return] {
  def await(): Return
  def await(duration: FiniteDuration): Option[Return]
}
