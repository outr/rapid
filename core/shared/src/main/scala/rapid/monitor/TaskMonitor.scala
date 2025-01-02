package rapid.monitor

import rapid.{Fiber, Task}

trait TaskMonitor {
  def pureCreated[T](task: Task.Pure[T]): Unit
  def singleCreated[T](task: Task.Single[T]): Unit
  def chainedCreated[T](task: Task.Chained[T]): Unit
  def errorCreated[T](task: Task.Error[T]): Unit
  def completableCreated[T](task: Task.Completable[T]): Unit
  def completableSuccess[T](task: Task.Completable[T], result: T): Unit
  def completableFailure[T](task: Task.Completable[T], throwable: Throwable): Unit
  def fiberCreated[T](fiber: Fiber[T]): Unit
  def start[T](task: Task[T]): Unit
  def success[T](task: Task[T], result: T): Unit
  def error[T](task: Task[T], throwable: Throwable): Unit
}