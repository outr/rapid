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

  override def created[T](task: Task[T]): Unit = created(task.getClass.getSimpleName, task)

  override def fiberCreated[T](fiber: Fiber[T], from: Task[T]): Unit = {
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
