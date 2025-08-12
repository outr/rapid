package rapid

import rapid.task.CompletableTask

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

trait RapidPlatform {
  def executionContext: ExecutionContext

  def supportsCancel: Boolean

  def createFiber[Return](task: Task[Return]): Fiber[Return]

  def fireAndForget(task: Task[_]): Unit

  def sleep(duration: FiniteDuration): Task[Unit]
}
