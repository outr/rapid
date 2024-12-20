package rapid

/**
 * Provides convenience functionality for running an application as a Task
 */
trait RapidApp {
  def main(args: Array[String]): Unit = {
    run(args.toList).sync()
  }

  def run(args: List[String]): Task[Unit]
}