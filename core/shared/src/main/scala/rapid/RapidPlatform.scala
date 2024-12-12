package rapid

import scala.concurrent.ExecutionContext

trait RapidPlatform {
  def executionContext: ExecutionContext

  def createFiber[Return](task: Task[Return]): Fiber[Return]
}
