// ParallelStream.scala
package rapid

import scala.collection.mutable.ListBuffer

case class ParallelStream[T, R](
                                 stream: Stream[T],
                                 forge: Forge[T, Option[R]],
                                 maxThreads: Int  = ParallelStream.DefaultMaxThreads,
                                 maxBuffer: Int   = ParallelStream.DefaultMaxBuffer
                               ) {
  def collect[U](pf: PartialFunction[R, U]): ParallelStream[T, U] =
    copy[T, U](
      forge = forge.map(optR => optR.flatMap(r => pf.lift(r)))
    )

  def drain: Task[Unit] =
    Task.flatMap { _ =>
      val c = Task.completable[Unit]
      compile(_ => (), _ => c.success(()))
      c
    }

  def count: Task[Int] =
    Task.flatMap { _ =>
      val c = Task.completable[Int]
      compile(_ => (), c.success)
      c
    }

  def toList: Task[List[R]] =
    Task.flatMap { _ =>
      val buf = ListBuffer.empty[R]
      val c   = Task.completable[List[R]]
      compile(buf += _, _ => c.success(buf.toList))
      c
    }

  def fold[U](initial: U)(f: (U, R) => Task[U]): Task[U] =
    Task.flatMap { _ =>
      var acc = initial
      val c   = Task.completable[U]
      compile(r => acc = f(acc, r).sync(), _ => c.success(acc))
      c
    }

  protected def compile(handle: R => Unit, complete: Int => Unit): Unit =
    ParallelStreamProcessor(this, handle, complete)
}

object ParallelStream {
  val DefaultMaxThreads = Runtime.getRuntime.availableProcessors * 2
  val DefaultMaxBuffer  = 100_000
}