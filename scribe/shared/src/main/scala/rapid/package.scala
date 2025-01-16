import scribe.Logger

package object rapid {
  implicit class LoggerExtras(val logger: Logger) extends AnyVal {
    def rapid: RapidLoggerSupport = new RapidLoggerWrapper(logger)
  }
}
