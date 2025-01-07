package rapid

import scala.collection.mutable.ListBuffer

case class ParallelStream[T, R](stream: Stream[T],
                                forge: Forge[T, Option[R]],
                                maxThreads: Int,
                                maxBuffer: Int) {
  def collect[Return](f: PartialFunction[R, Return]): ParallelStream[T, Return] = copy[T, Return](
    forge = this.forge.map {
      case Some(r) => r match {
        case f(r) => Some(r)
        case _ => None
      }
      case None => None
    }
  )

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

  def fold[Return](initial: Return)(f: (Return, R) => Task[Return]): Task[Return] = Task.flatMap { _ =>
    var value = initial
    val completable = Task.completable[Return]
    compile(r => {
      value = f(value, r).sync()
    }, _ => completable.success(value))
    completable
  }

  protected def compile(handle: R => Unit, complete: Int => Unit): Unit =
    ParallelStreamProcessor(this, handle, complete)
}

object ParallelStream {
  val DefaultMaxThreads: Int = Runtime.getRuntime.availableProcessors * 2
  val DefaultMaxBuffer: Int = 100_000
}