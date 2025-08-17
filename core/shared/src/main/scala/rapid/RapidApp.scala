package rapid

import rapid.task.TaskCombinators.start
import scala.util.{Failure, Success, Try}
import rapid.Task
import rapid.task.TaskCombinators._  // for `.attempt`, `.flatMap`, etc.

/**
 * Provides convenience functionality for running an application as a Task
 */
trait RapidApp {
  def main(args: Array[String]): Unit = {
    // FIXED: Converts Either to Try to match `result` signature
    run(args.toList).attempt.flatMap(e => result(e.toTry)).sync()
  }

  def run(args: List[String]): Task[Unit]

  def result(result: Try[Unit]): Task[Unit] = result match {
    case Success(_) => Task.unit
    case Failure(t) => Task(t.printStackTrace())
  }
}
