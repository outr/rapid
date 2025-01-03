package rapid.task

import rapid.Task

import scala.util.{Failure, Success, Try}

class CompletableTask[Return] extends Task[Return] {
  @volatile private var result: Option[Try[Return]] = None

  def success(result: Return): Unit = synchronized {
    this.result = Some(Success(result))
    notifyAll()
  }

  def failure(throwable: Throwable): Unit = synchronized {
    this.result = Some(Failure(throwable))
    notifyAll()
  }

  override def sync(): Return = synchronized {
    while (result.isEmpty) {
      wait()
    }
    result.get.get
  }

  override def toString: String = "Completable"
}
