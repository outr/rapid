package rapid

sealed trait ExecutionMode

object ExecutionMode {
  case object Synchronous extends ExecutionMode
  case object Asynchronous extends ExecutionMode
}