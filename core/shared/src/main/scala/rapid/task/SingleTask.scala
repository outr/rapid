package rapid.task

import rapid.Task

case class SingleTask[Return](f: () => Return) extends AnyVal with Task[Return] {
  // Don't implement executeFast - SingleTask needs async execution to avoid deadlocks
  // The function f() could do anything including blocking I/O or calling .sync()
  
  // DON'T override sync() - SingleTask MUST use trampoline for safety
  // The function f() might recursively call .sync() causing stack overflow
  
  override def toString: String = "Single"
}