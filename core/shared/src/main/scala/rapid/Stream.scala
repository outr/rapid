package rapid

import java.io.{File, FileInputStream, InputStream}
import java.nio.file.Path
import scala.io.Source

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
   * Chunks the stream's values into vectors of size `size` (except possibly the last).
   *
   * @param chunkSize the maximum size of each chunk
   * @return a new stream where each element is a vector of `Return` values
   */
  def chunk(chunkSize: Int = 1024): Stream[Vector[Return]] = new Stream(task.map { i =>
    new Iterator[Vector[Return]] {
      override def hasNext: Boolean = i.hasNext

      override def next(): Vector[Return] = {
        if (!hasNext) throw new NoSuchElementException("no more chunks")

        val buffer = new scala.collection.mutable.ArrayBuffer[Return](chunkSize)
        var count = 0
        while (count < size && i.hasNext) {
          buffer += i.next()
          count += 1
        }

        buffer.toVector
      }
    }
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
   * Drains the stream and fully evaluates it.
   */
  def drain: Task[Unit] = count.unit

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

  def par[R](maxThreads: Int = ParallelStream.DefaultMaxThreads,
             maxBuffer: Int = ParallelStream.DefaultMaxBuffer)
            (f: Return => Task[R]): ParallelStream[Return, R] = ParallelStream(
    stream = this,
    f = f,
    maxThreads = maxThreads,
    maxBuffer = maxBuffer
  )
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

  /**
   * Creates a stream from an iterator task.
   *
   * @param iterator the iterator task
   * @tparam Return the type of the values
   * @return a new stream that emits the values in the iterator
   */
  def fromIterator[Return](iterator: Task[Iterator[Return]]): Stream[Return] = new Stream[Return](iterator)

  /**
   * Creates a Byte stream from the NIO Path
   *
   * @param path the path to the file
   * @return a new stream that emits Bytes
   */
  def fromPath(path: Path): Stream[Byte] = fromFile(path.toFile)

  /**
   * Creates a Byte stream from the Java File
   *
   * @param file the file to load
   * @return a new stream that emits Bytes
   */
  def fromFile(file: File): Stream[Byte] = fromInputStream(Task(new FileInputStream(file)))

  /**
   * Creates a Byte stream from the InputStream task
   *
   * @param input the InputStream task
   * @return a new stream that emits Bytes
   */
  def fromInputStream(input: Task[InputStream]): Stream[Byte] = new Stream[Byte](input.map { is =>
    var finished = false

    val baseIterator = Iterator.continually(is.read())
      .takeWhile(_ != -1)
      .map(_.toByte)

    new Iterator[Byte] {
      def hasNext: Boolean = {
        val hasMore = baseIterator.hasNext
        if (!hasMore && !finished) {
          finished = true
          is.close()
        }
        hasMore
      }

      def next(): Byte = {
        if (!hasNext) throw new NoSuchElementException
        baseIterator.next()
      }
    }
  })

  def task[Return](stream: Stream[Return]): Task[Iterator[Return]] = stream.task
}