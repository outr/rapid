package rapid

import scribe.Logger

object logger extends RapidLoggerSupport {
  implicit class LoggerExtras(val logger: Logger) extends AnyVal {
    def rapid: RapidLoggerSupport = new RapidLoggerWrapper(logger)
  }

  implicit class ScribeTaskExtras[R](val task: Task[R]) extends AnyVal {
    def logErrors: Task[R] = task.handleError { throwable =>
      error(throwable).error(throwable)
    }
  }
}