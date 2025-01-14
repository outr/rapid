package rapid

import scala.util.{Failure, Success, Try}

/**
 * Provides convenience functionality for running an application as a Task
 */
trait RapidApp {
  def main(args: Array[String]): Unit = {
    run(args.toList).attempt.flatMap(result).sync()
  }

  def run(args: List[String]): Task[Unit]

  def result(result: Try[Unit]): Task[Unit] = result match {
    case Success(_) => Task.unit
    case Failure(t) => Task(t.printStackTrace())
  }
}