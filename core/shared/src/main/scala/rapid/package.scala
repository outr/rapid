package rapid

// Required to bring .start() and other extensions into scope
import rapid.task.TaskCombinators.given

// Optional: brings other non-extension members (if needed)
import rapid.task.TaskCombinators._

// For using FiniteDuration in await
import scala.concurrent.duration.FiniteDuration
