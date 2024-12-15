package rapid

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import scala.annotation.tailrec
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.DurationInt

case class ParallelStream[T, R](stream: Stream[T],
                                f: T => Task[R],
                                maxThreads: Int,
                                maxBuffer: Int) {
  def drain: Task[Unit] = Task.unit.flatMap { _ =>
    val completable = Task.completable[Unit]
    ParallelUnorderedStreamProcessor(
      stream = this,
      handle = (_: R) => (),
      complete = (_: Int) => completable.success(())
    )
    completable
  }

  def count: Task[Int] = Task.unit.flatMap { _ =>
    val completable = Task.completable[Int]
    ParallelUnorderedStreamProcessor(
      stream = this,
      handle = (_: R) => (),
      complete = completable.success
    )
    completable
  }

  def toList: Task[List[R]] = Task.unit.flatMap { _ =>
    val list = ListBuffer.empty[R]
    val completable = Task.completable[List[R]]
    ParallelUnorderedStreamProcessor(
      stream = this,
      handle = list.addOne,
      complete = (_: Int) => completable.success(list.toList)
    )
    completable
  }
}

object ParallelStream {
  val DefaultMaxThreads: Int = Runtime.getRuntime.availableProcessors * 2
  val DefaultMaxBuffer: Int = 1_000
}