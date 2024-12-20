package rapid

import scala.concurrent.ExecutionContext

object Platform extends RapidPlatform {
  override def executionContext: ExecutionContext = org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global

  override def supportsCancel: Boolean = false

  override def createFiber[Return](task: Task[Return]): Fiber[Return] = new FutureFiber[Return](task)
}