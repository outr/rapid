package rapid

import scala.collection.mutable.ListBuffer

case class ParallelStream[T, R](stream: Stream[T],
                                f: T => Task[R],
                                maxThreads: Int,
                                maxBuffer: Int) {
  def drain: Task[Unit] = Task.flatMap { _ =>
    val completable = Task.completable[Unit]
    compile(_ => (), _ => completable.success(()))
    completable
  }

  def count: Task[Int] = Task.flatMap { _ =>
    val completable = Task.completable[Int]
    compile(_ => (), completable.success)
    completable
  }

  def toList: Task[List[R]] = Task.flatMap { _ =>
    val list = ListBuffer.empty[R]
    val completable = Task.completable[List[R]]
    compile(list.addOne, _ => completable.success(list.toList))
    completable
  }

  protected def compile(handle: R => Unit, complete: Int => Unit): Unit =
    ParallelStreamProcessor(this, handle, complete)
}

object ParallelStream {
  val DefaultMaxThreads: Int = Runtime.getRuntime.availableProcessors * 2
  val DefaultMaxBuffer: Int = 100_000
}