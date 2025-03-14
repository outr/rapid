package rapid

import java.io.{BufferedInputStream, File, FileInputStream, InputStream}
import java.nio.file.Path
import scala.io.Source

/**
 * Represents a pull-based stream of values of type `Return`.
 *
 * @tparam Return the type of the values produced by this stream
 */
class Stream[+Return](private val task: Task[Iterator[Return]]) extends AnyVal {
  /**
   * Filters the values in the stream using the given predicate.
   *
   * @param p the predicate to test the values
   * @return a new stream with the values that satisfy the predicate
   */
  def filter(p: Return => Boolean): Stream[Return] = new Stream(task.map(_.filter(p)))

  def filterNot(p: Return => Boolean): Stream[Return] = filter(r => !p(r))

  /**
   * Builds a new stream by applying a partial function to all elements of this stream on which the function is defined.
   *
   * @param f the partial function to apply
   * @tparam T the new return type
   */
  def collect[T](f: PartialFunction[Return, T]): Stream[T] = new Stream(task.map { iterator =>
    iterator.collect(f)
  })

  /**
   * Takes values from the stream while the given predicate holds.
   *
   * @param p the predicate to test the values
   */
  def takeWhile(p: Return => Boolean): Stream[Return] = new Stream(task.map(_.takeWhile(p)))

  /**
   * Takes n values from the stream and disregards the rest.
   *
   * @param n the number of values to take from the stream
   */
  def take(n: Int): Stream[Return] = new Stream(task.map(_.take(n)))

  /**
   * Transforms the values in the stream using the given function.
   *
   * @param f the function to transform the values
   * @tparam T the type of the transformed values
   * @return a new stream with the transformed values
   */
  def map[T](f: Return => T): Stream[T] = new Stream(task.map(_.map(f)))

  /**
   * Similar to map, but doesn't change the result. Allows doing something with each value without changing the result.
   *
   * @param f the function to handle each value
   * @return Stream[Return]
   */
  def foreach(f: Return => Unit): Stream[Return] = map { r =>
    f(r)
    r
  }

  /**
   * Transforms the values in the stream to include the index of the value within the stream
   */
  def zipWithIndex: Stream[(Return, Int)] = new Stream(task.map { iterator =>
    iterator.zipWithIndex
  })

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

  def evalForge[R >: Return, T](forge: Forge[R, T]): Stream[T] = new Stream(task.map { iterator =>
    iterator.map(r => forge(r)).map(_.sync())
  })

  /**
   * Similar to evalMap, but doesn't change the result. Allows doing something with each value without changing the
   * result.
   *
   * @param f the function to handle each value
   * @return Stream[Return]
   */
  def evalForeach(f: Return => Task[Unit]): Stream[Return] = evalMap { r =>
    f(r).map(_ => r)
  }

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
        while (count < chunkSize && i.hasNext) {
          buffer += i.next()
          count += 1
        }

        buffer.toVector
      }
    }
  })

  /**
   * Creates a grouping Stream expecting the group delineation is the natural sort order of the results.
   *
   * @param grouper the grouping function
   * @tparam G the group key
   */
  def groupSequential[G, R >: Return](grouper: R => G): Stream[Grouped[G, R]] = new Stream(task.map { iterator =>
    GroupedIterator(iterator, grouper)
  })

  /**
   * Groups at a separator
   *
   * @param separator returns true if this entry represents a separator
   */
  def group(separator: Return => Boolean): Stream[List[Return]] = new Stream(task.map { iterator =>
    new GroupingIterator[Return](iterator, separator)
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
   * Appends another stream to this stream.
   *
   * @param that the stream to append
   * @tparam T the type of the values in the appended stream
   * @return a new stream with the values from both streams
   */
  def ++[T >: Return](that: => Stream[T]): Stream[T] = append(that)

  /**
   * Drains the stream and fully evaluates it.
   */
  def drain: Task[Unit] = Task {
    val it = task.sync()
    while (it.hasNext) it.next()
    ()
  }

  /**
   * Cycles through all results but only returns the last element. Will error if the Stream is empty.
   */
  def last: Task[Return] = task.map(_.reduce((_, b) => b))

  /**
   * Cycles through all results but only returns the last element or None if the stream is empty.
   */
  def lastOption: Task[Option[Return]] = task.map { iterator =>
    iterator.reduceOption((_, b) => b)
  }

  /**
   * Folds through the stream returning the last value
   *
   * @param initial the initial T to start with
   * @param f the processing function
   * @tparam T the resulting type
   * @return Task[T]
   */
  def fold[T](initial: T)(f: (T, Return) => Task[T]): Task[T] = Task {
    var result = initial
    val it = task.sync()
    while (it.hasNext) {
      result = f(result, it.next()).sync()
    }
    result
  }

  /**
   * Grabs only the first result from the stream.
   */
  def first: Task[Return] = take(1).last

  /**
   * Grabs only the first element or None if the stream is empty.
   */
  def firstOption: Task[Option[Return]] = take(1).lastOption

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

  def par[T, R >: Return](maxThreads: Int = ParallelStream.DefaultMaxThreads,
                          maxBuffer: Int = ParallelStream.DefaultMaxBuffer)
                         (forge: Forge[R, T]): ParallelStream[R, T] = ParallelStream(
    stream = this,
    forge = forge.map(Option.apply),
    maxThreads = maxThreads,
    maxBuffer = maxBuffer
  )
}

object Stream {
  /**
   * Creates a stream with a variable number of entries
   *
   * @param values the variable number of entries
   */
  def apply[Return](values: Return*): Stream[Return] = emits(values)

  /**
   * Creates a stream that emits a single value.
   *
   * @param value the value to emit
   * @tparam Return the type of the value
   * @return a new stream that emits the value
   */
  def emit[Return](value: Return): Stream[Return] = new Stream[Return](Task.pure(Iterator.single(value)))

  /**
   * Creates an empty stream.
   *
   * @tparam Return the type of the values in the stream
   * @return a new empty stream
   */
  def empty[Return]: Stream[Return] = new Stream[Return](Task.pure(Iterator.empty))

  /**
   * Creates a stream from a sequence of values.
   *
   * @param seq the sequence of values
   * @tparam Return the type of the values
   * @return a new stream that emits the values in the sequence
   */
  def emits[Return](seq: Seq[Return]): Stream[Return] = fromIterator[Return](Task(seq.iterator)) // TODO: Figure out why .toList is necessary

  /**
   * Creates a stream from an iterator task.
   *
   * @param iterator the iterator task
   * @tparam Return the type of the values
   * @return a new stream that emits the values in the iterator
   */
  def fromIterator[Return](iterator: Task[Iterator[Return]]): Stream[Return] = new Stream[Return](iterator)

  /**
   * Forces a Task[Stream] into Stream
   */
  def force[Return](stream: Task[Stream[Return]]): Stream[Return] = new Stream[Return](stream.flatMap(_.task))

  /**
   * Merges an Iterator of Streams together into one lazily loading Stream
   */
  def merge[Return](streams: Task[Iterator[Stream[Return]]]): Stream[Return] = force(streams.map { iterator =>
    new Stream(Task {
      iterator.flatMap(_.task.sync())
    })
  })

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
  def fromFile(file: File): Stream[Byte] = fromInputStream(Task(new BufferedInputStream(new FileInputStream(file))))

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