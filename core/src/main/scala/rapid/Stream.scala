package rapid

import java.util.concurrent.Semaphore

/**
 * Represents a pull-based stream of values of type `Return`.
 *
 * @tparam Return the type of the values produced by this stream
 */
class Stream[Return](private val task: Task[Iterator[Return]]) extends AnyVal {
  /**
   * Filters the values in the stream using the given predicate.
   *
   * @param p the predicate to test the values
   * @return a new stream with the values that satisfy the predicate
   */
  def filter(p: Return => Boolean): Stream[Return] = new Stream(task.map(_.filter(p)))

  /**
   * Takes values from the stream while the given predicate holds.
   *
   * @param p the predicate to test the values
   * @return a new stream with the values that satisfy the predicate
   */
  def takeWhile(p: Return => Boolean): Stream[Return] = new Stream(task.map(_.takeWhile(p)))

  /**
   * Transforms the values in the stream using the given function.
   *
   * @param f the function to transform the values
   * @tparam T the type of the transformed values
   * @return a new stream with the transformed values
   */
  def map[T](f: Return => T): Stream[T] = new Stream(task.map(_.map(f)))

  /**
   * Transforms the values in the stream using the given function that returns a new stream.
   *
   * @param f the function to transform the values into new streams
   * @tparam T the type of the values in the new streams
   * @return a new stream with the transformed values
   */
  def flatMap[T](f: Return => Stream[T]): Stream[T] = new Stream(task.map { iterator =>
    iterator.flatMap(r => f(r).task.sync())
  })

  /**
   * Transforms the values in the stream using the given function that returns a task.
   *
   * @param f the function to transform the values into tasks
   * @tparam T the type of the values in the tasks
   * @return a new stream with the transformed values
   */
  def evalMap[T](f: Return => Task[T]): Stream[T] = new Stream(task.map { iterator =>
    iterator.map(f).map(_.sync())
  })

  /**
   * Appends another stream to this stream.
   *
   * @param that the stream to append
   * @tparam T the type of the values in the appended stream
   * @return a new stream with the values from both streams
   */
  def append[T >: Return](that: => Stream[T]): Stream[T] = new Stream(Task {
    val iterator1 = task.sync()
    val iterator2 = that.task.sync()
    iterator1 ++ iterator2
  })

  /**
   * Converts the stream to a list.
   *
   * @return a task that produces a list of the values in the stream
   */
  def toList: Task[List[Return]] = task.map(_.toList)

  /**
   * Counts the number of elements in the stream and fully evaluates it.
   *
   * @return a `Task[Int]` representing the total number of entries evaluated
   */
  def count: Task[Int] = task.map(_.size)
}

/*trait Stream[Return] { stream =>
  /**
   * Produces the next value in the stream, if any.
   *
   * @return a `Pull` that produces an optional pair of the next value and the remaining stream
   */
  def pull: Pull[Option[(Return, Stream[Return])]]

  /**
   * Transforms the values in the stream using the given function that returns a task, with a maximum concurrency.
   *
   * @param maxConcurrency the maximum number of concurrent tasks
   * @param f the function to transform the values into tasks
   * @tparam T the type of the values in the tasks
   * @return a new stream with the transformed values
   */
  def parEvalMap[T](maxConcurrency: Int)(f: Return => Task[T]): Stream[T] = new Stream[T] {
    val semaphore = new Semaphore(maxConcurrency)

    def pull: Pull[Option[(T, Stream[T])]] = Pull.suspend {
      if (semaphore.tryAcquire()) {
        stream.pull.flatMap {
          case Some((head, tail)) =>
            val task = f(head)
            Pull.suspend {
              task.map { result =>
                semaphore.release()
                Option(result -> tail.parEvalMap(maxConcurrency)(f))
              }.toPull
            }
          case None =>
            semaphore.release()
            Pull.pure(None)
        }
      } else {
        Pull.suspend(pull)
      }
    }
  }
}*/

object Stream {
  /**
   * Creates a stream that emits a single value.
   *
   * @param value the value to emit
   * @tparam Return the type of the value
   * @return a new stream that emits the value
   */
  def emit[Return](value: Return): Stream[Return] = new Stream[Return](Task.pure(List(value).iterator))

  /**
   * Creates an empty stream.
   *
   * @tparam Return the type of the values in the stream
   * @return a new empty stream
   */
  def empty[Return]: Stream[Return] = new Stream[Return](Task.pure(Nil.iterator))

  /**
   * Creates a stream from a sequence of values.
   *
   * @param seq the sequence of values
   * @tparam Return the type of the values
   * @return a new stream that emits the values in the sequence
   */
  def emits[Return](seq: Seq[Return]): Stream[Return] = new Stream[Return](Task(seq.iterator))

  def task[Return](stream: Stream[Return]): Task[Iterator[Return]] = stream.task
}