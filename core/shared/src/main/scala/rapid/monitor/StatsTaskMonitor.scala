package rapid.monitor

import rapid.{Fiber, Task}

class StatsTaskMonitor extends TaskMonitor {
  protected var map = Map.empty[String, Int]
  protected var tasks = Set.empty[Task[_]]

  private def mod(name: String, mod: Int): Unit = synchronized {
    val c = map.getOrElse(name, 0)
    map += name -> (c + mod)
  }

  private def created(name: String, task: Task[_]): Unit = {
    mod(name, 1)
    mod("Tasks", 1)
    synchronized {
      tasks += task
    }
  }

  override def pureCreated[T](task: Task.Pure[T]): Unit = created("Pure", task)
  override def singleCreated[T](task: Task.Single[T]): Unit = created("Single", task)
  override def chainedCreated[T](task: Task.Chained[T]): Unit = created("Chained", task)
  override def errorCreated[T](task: Task.Error[T]): Unit = created("Error", task)
  override def completableCreated[T](task: Task.Completable[T]): Unit = created("Completable", task)
  override def completableSuccess[T](task: Task.Completable[T], result: T): Unit = mod("CompletableCompleted", 1)
  override def completableFailure[T](task: Task.Completable[T], throwable: Throwable): Unit = mod("CompletableFailure", 1)
  override def fiberCreated[T](fiber: Fiber[T]): Unit = {
    mod("Fibers", 1)
    mod("FibersActive", 1)
  }
  override def start[T](task: Task[T]): Unit = {
    mod("TaskStarted", 1)
    mod("TasksRunning", 1)
  }
  override def success[T](task: Task[T], result: T): Unit = {
    mod("TaskSuccess", 1)
    mod("TasksRunning", -1)
    synchronized {
      tasks -= task
    }
  }
  override def error[T](task: Task[T], throwable: Throwable): Unit = {
    mod("TaskError", 1)
    mod("TasksRunning", -1)
    synchronized {
      tasks -= task
    }
  }

  def report(): String = {
    val entries = map.map {
      case (key, value) => s"\t$key: $value"
    }.mkString("\n")
    s"Task Report:\n$entries\n\tNot Consumed: ${tasks.size}"
  }
}
