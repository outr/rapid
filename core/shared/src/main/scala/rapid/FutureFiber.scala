package rapid

import scala.concurrent.Future

class FutureFiber[Return](val task: Task[Return]) extends Fiber[Return] {
  // Assign unique ID on creation using thread-local generator
  override val id: Long = FiberIdGenerator.nextId()
  
  private val future: Future[Return] = Future(task.sync())(Platform.executionContext)

  override def sync(): Return = future.value match {
    case Some(value) => value.get
    case None => throw new RuntimeException("Cannot wait")
  }
  
  override def toString: String = s"FutureFiber(id=$id)"
}