package rapid

import scribe.mdc.MDC
import scribe.{Level, LogFeature, LogRecord, Logger}
import sourcecode.{FileName, Line, Name, Pkg}

class RapidLoggerWrapper(val wrapped: Logger) extends AnyVal with RapidLoggerSupport {
  override def log(record: LogRecord): Task[Unit] = Task(wrapped.log(record))

  override def log(level: Level, mdc: MDC, features: LogFeature*)
                  (implicit pkg: Pkg, fileName: FileName, name: Name, line: Line): Task[Unit] =
    Task(wrapped.log(level, mdc, features: _*)(pkg, fileName, name, line))
}
