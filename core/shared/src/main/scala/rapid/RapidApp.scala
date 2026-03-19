package rapid

import scala.util.{Failure, Success, Try}

/**
 * Provides convenience functionality for running an application as a Task
 */
trait RapidApp extends RapidLoggerSupport {
  def main(args: Array[String]): Unit = {
    val exitCode = run(args.toList).attempt.flatMap(result).sync()
    if (exitCode != 0) sys.exit(exitCode)
  }

  def run(args: List[String]): Task[Unit]

  def result(result: Try[Unit]): Task[Int] = result match {
    case Success(_) => Task.pure(0)
    case Failure(t) => error(t).map(_ => 1)
  }
}
