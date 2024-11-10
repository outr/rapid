package rapid

import java.util.concurrent.Semaphore

/**
 * Represents a pull-based stream of values of type `Return`.
 *
 * @tparam Return the type of the values produced by this stream
 */
trait Stream[Return] { stream =>
  /**
   * Produces the next value in the stream, if any.
   *
   * @return a `Pull` that produces an optional pair of the next value and the remaining stream
   */
  def pull: Pull[Option[(Return, Stream[Return])]]

  /**
   * Transforms the values in the stream using the given function.
   *
   * @param f the function to transform the values
   * @tparam T the type of the transformed values
   * @return a new stream with the transformed values
   */
  def map[T](f: Return => T): Stream[T] = new Stream[T] {
    def pull: Pull[Option[(T, Stream[T])]] = stream.pull.flatMap {
      case Some((head, tail)) => Pull.pure(Some(f(head) -> tail.map(f)))
      case None => Pull.pure(None)
    }
  }

  /**
   * Transforms the values in the stream using the given function that returns a new stream.
   *
   * @param f the function to transform the values into new streams
   * @tparam T the type of the values in the new streams
   * @return a new stream with the transformed values
   */
  def flatMap[T](f: Return => Stream[T]): Stream[T] = new Stream[T] {
    def pull: Pull[Option[(T, Stream[T])]] = {
      def go(s: Stream[Return]): Pull[Option[(T, Stream[T])]] = s.pull.flatMap {
        case Some((head, tail)) => f(head).pull.flatMap {
          case Some((fh, ft)) => Pull.pure(Some(fh -> ft.append(tail.flatMap(f))))
          case None => go(tail)
        }
        case None => Pull.pure(None)
      }
      go(stream)
    }
  }

  /**
   * Appends another stream to this stream.
   *
   * @param that the stream to append
   * @tparam T the type of the values in the appended stream
   * @return a new stream with the values from both streams
   */
  def append[T >: Return](that: => Stream[T]): Stream[T] = new Stream[T] {
    def pull: Pull[Option[(T, Stream[T])]] = stream.pull.flatMap {
      case Some((head, tail)) => Pull.pure(Some(head -> tail.append(that)))
      case None => that.pull
    }
  }

  /**
   * Takes values from the stream while the given predicate holds.
   *
   * @param p the predicate to test the values
   * @return a new stream with the values that satisfy the predicate
   */
  def takeWhile(p: Return => Boolean): Stream[Return] = new Stream[Return] {
    def pull: Pull[Option[(Return, Stream[Return])]] = stream.pull.flatMap {
      case Some((head, tail)) =>
        if (p(head)) Pull.pure(Some(head -> tail.takeWhile(p)))
        else Pull.pure(None)
      case None => Pull.pure(None)
    }
  }

  /**
   * Filters the values in the stream using the given predicate.
   *
   * @param p the predicate to test the values
   * @return a new stream with the values that satisfy the predicate
   */
  def filter(p: Return => Boolean): Stream[Return] = new Stream[Return] {
    def pull: Pull[Option[(Return, Stream[Return])]] = {
      def go(s: Stream[Return]): Pull[Option[(Return, Stream[Return])]] = s.pull.flatMap {
        case Some((head, tail)) =>
          if (p(head)) Pull.pure(Some(head -> new Stream[Return] {
            def pull: Pull[Option[(Return, Stream[Return])]] = go(tail)
          }))
          else go(tail)
        case None => Pull.pure(None)
      }
      go(stream)
    }
  }

  /**
   * Transforms the values in the stream using the given function that returns a task.
   *
   * @param f the function to transform the values into tasks
   * @tparam T the type of the values in the tasks
   * @return a new stream with the transformed values
   */
  def evalMap[T](f: Return => Task[T]): Stream[T] = new Stream[T] {
    def pull: Pull[Option[(T, Stream[T])]] = stream.pull.flatMap {
      case Some((head, tail)) =>
        Pull.suspend {
          f(head).map(result => Option(result -> tail.evalMap(f))).toPull
        }
      case None => Pull.pure(None)
    }
  }

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

  /**
   * Converts the stream to a list.
   *
   * @return a task that produces a list of the values in the stream
   */
  def toList: Task[List[Return]] = {
    def loop(stream: Stream[Return], acc: List[Return]): Pull[List[Return]] = {
      stream.pull.flatMap {
        case Some((head, tail)) => Pull.suspend(loop(tail, acc :+ head))
        case None => Pull.pure(acc)
      }
    }
    loop(this, List.empty).toTask
  }

  /**
   * Counts the number of elements in the stream and fully evaluates it.
   *
   * @return a `Task[Int]` representing the total number of entries evaluated
   */
  def count: Task[Int] = {
    def loop(stream: Stream[Return], acc: Int): Pull[Int] = {
      stream.pull.flatMap {
        case Some((_, tail)) => Pull.suspend(loop(tail, acc + 1))
        case None => Pull.pure(acc)
      }
    }
    loop(this, 0).toTask
  }
}

object Stream {
  /**
   * Creates a stream that emits a single value.
   *
   * @param value the value to emit
   * @tparam Return the type of the value
   * @return a new stream that emits the value
   */
  def emit[Return](value: Return): Stream[Return] = new Stream[Return] {
    def pull: Pull[Option[(Return, Stream[Return])]] = Pull.pure(Some(value -> empty))
  }

  /**
   * Creates an empty stream.
   *
   * @tparam Return the type of the values in the stream
   * @return a new empty stream
   */
  def empty[Return]: Stream[Return] = new Stream[Return] {
    def pull: Pull[Option[(Return, Stream[Return])]] = Pull.pure(None)
  }

  /**
   * Creates a stream from a list of values.
   *
   * @param list the list of values
   * @tparam Return the type of the values
   * @return a new stream that emits the values in the list
   */
  def fromList[Return](list: List[Return]): Stream[Return] = list match {
    case Nil => empty
    case head :: tail => Stream.emit(head).append(fromList(tail))
  }
}