package rapid

import scala.concurrent.duration.Duration

trait BlockableFiber[Return] extends Fiber[Return] {
  def await(): Return

  def await(duration: Duration): Option[Return]
}
