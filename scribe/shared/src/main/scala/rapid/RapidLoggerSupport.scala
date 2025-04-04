package rapid

import scribe.mdc.MDC
import scribe.{Level, LogFeature, LogRecord, Logger, LoggerSupport}
import sourcecode.{FileName, Line, Name, Pkg}

trait RapidLoggerSupport extends Any with LoggerSupport[Task[Unit]] {
  override def log(record: => LogRecord): Task[Unit] = Task(Logger(record.className)
    .log(record.copy(timeStamp = System.currentTimeMillis())))

  override def log(level: Level, mdc: MDC, features: LogFeature*)
                  (implicit pkg: Pkg, fileName: FileName, name: Name, line: Line): Task[Unit] =
    log(LoggerSupport(level, Nil, pkg, fileName, name, line, mdc).withFeatures(features: _*))
}
