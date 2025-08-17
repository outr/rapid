package benchmark

import rapid.task.TaskCombinators._         // Task.sleep / map / etc.
import rapid.BlockableTask                  // brings .await extension into scope

import rapid.{Forge, RapidApp, Task}

object ParallelTesting extends RapidApp {
  override def run(args: List[String]): Task[Unit] = Task {
    val total = 10_000_000L
    // âœ… pick this:
    val forge: Forge[Long, Long] = l => Task(l).map(x => x * x)
    val syncTask = rapid.Stream.emits(0L until total)
      .evalForge(forge)
      .fold(0L)((current, total) => Task(total + current))
    val parallelTask = rapid.Stream.emits(0L until total)
      .par(maxThreads = 32) { l =>
        forge(l)
      }
      .fold(0L)((current, total) => Task(total + current))
    time(syncTask)
    time(parallelTask)
  }

  private def time(task: Task[Long]): Unit = {
    val start = System.currentTimeMillis()
    val result = task.sync()
    println(s"Result: $result in ${(System.currentTimeMillis() - start) / 1000.0} seconds")
  }
}