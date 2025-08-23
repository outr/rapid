package rapid.concurrency

import rapid.Task

import scala.concurrent.duration.FiniteDuration

trait ConcurrencyManager {
  def schedule(delay: FiniteDuration, execution: TaskExecution[_]): Unit

  def fire(execution: TaskExecution[_]): Unit

  def sync[Return](task: Task[Return]): Return = new TaskExecution[Return](task).sync()
}

object ConcurrencyManager {
  var active: ConcurrencyManager = VirtualThreadConcurrencyManager
}