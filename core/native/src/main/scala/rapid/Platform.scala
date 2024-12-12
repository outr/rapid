package rapid

import scala.concurrent.ExecutionContext

object Platform extends RapidPlatform {
  override def executionContext: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global

  override def createFiber[Return](task: Task[Return]): Fiber[Return] = new FutureBlockableFiber[Return](task)
}
