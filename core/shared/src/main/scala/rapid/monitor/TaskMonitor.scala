package rapid.monitor

import rapid.{Fiber, Task}

trait TaskMonitor {
  def created[T](task: Task[T]): Unit
  def fiberCreated[T](fiber: Fiber[T], from: Task[T]): Unit
  def start[T](task: Task[T]): Unit
  def success[T](task: Task[T], result: T): Unit
  def error[T](task: Task[T], throwable: Throwable): Unit
}