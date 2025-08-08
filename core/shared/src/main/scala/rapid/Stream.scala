package rapid

import java.io.{BufferedInputStream, File, FileInputStream, InputStream}
import java.nio.file.Path
import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicReference}
import scala.annotation.tailrec
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.io.Source

/**
 * A lazy, pull-based stream abstraction for processing elements with full support for concurrency and composability.
 *
 * @tparam Return the type of the elements emitted by this stream
 */
class Stream[+Return](private val task: Task[Pull[Return]]) extends AnyVal {
  def transform[T](f: Return => Step[T]): Stream[T] =
    new Stream[T](task.map { outer =>
      val done = new AtomicBoolean(false)
      var current: Option[Pull[T]] = None

      () => {
        if (done.get()) None
        else {
          @annotation.tailrec
          def loop(): Option[T] = current match {
            case Some(inner) =>
              inner.pull() match {
                case some@Some(_) => some
                case None =>
                  current = None
                  loop()
              }

            case None =>
              outer.pull() match {
                case Some(a) =>
                  val stepRes = f(a)
                  stepRes match {
                    case e: Step.Emit[_] => Some(e.value.asInstanceOf[T])
                    case Step.Skip => loop()
                    case Step.Stop =>
                      done.set(true)
                      None
                    case c: Step.Concat[_] =>
                      current = Some(c.pull.asInstanceOf[Pull[T]])
                      loop()
                  }
                case None =>
                  done.set(true)
                  None
              }
          }

          loop()
        }
      }
    })

  /**
   * Filters the values in the stream using the given predicate.
   *
   * @param p the predicate to test the values
   * @return a new stream with the values that satisfy the predicate
   */
  def filter(p: Return => Boolean): Stream[Return] = new Stream[Return](
    task.map { pullR =>
      new Pull[Return] {
        override def pull(): Option[Return] = {
          @annotation.tailrec
          def loop(): Option[Return] = {
            pullR.pull() match {
              case Some(r) if p(r) => Some(r)
              case Some(_) => loop()
              case None => None
            }
          }
          loop()
        }
      }
    }
  )

  def filterNot(p: Return => Boolean): Stream[Return] = filter(r => !p(r))

  /**
   * Builds a new stream by applying a partial function to all elements of this stream on which the function is defined.
   *
   * @param f the partial function to apply
   * @tparam T the new return type
   */
  def collect[T](f: PartialFunction[Return, T]): Stream[T] = new Stream[T](
    task.map { pullR =>
      new Pull[T] {
        override def pull(): Option[T] = {
          @annotation.tailrec
          def loop(): Option[T] = {
            pullR.pull() match {
              case Some(r) => f.lift(r) match {
                case Some(t) => Some(t)
                case None => loop()
              }
              case None => None
            }
          }
          loop()
        }
      }
    }
  )

  /**
   * Takes values from the stream while the given predicate holds.
   *
   * @param p the predicate to test the values
   */
  def takeWhile(p: Return => Boolean): Stream[Return] = transform(a => if (p(a)) Step.Emit(a) else Step.Stop)

  /**
   * Takes n values from the stream and disregards the rest.
   *
   * @param n the number of values to take from the stream
   */
  def take(n: Int): Stream[Return] = {
    val counter = new AtomicInteger(0)
    transform { a =>
      if (counter.getAndIncrement() < n) Step.Emit(a)
      else Step.Stop
    }
  }

  /**
   * Takes elements from the stream while a predicate comparing the first element and the current element holds.
   *
   * Unlike `takeWhile`, which evaluates each element independently,
   * this method compares every subsequent element to the first one.
   * The first element is always emitted, and evaluation continues
   * as long as `predicate(first, current)` returns true.
   *
   * This is useful when you want to capture a run of values that
   * are similar or "close enough" to the initial element in some way.
   *
   * @param predicate A function that takes the first element and the current element,
   *                  and returns true if the current element should be included.
   * @return A new stream consisting of the first element and all subsequent elements
   *         that satisfy the predicate when compared to the first.
   *
   * @example
   * {{{
   *   Stream(10, 11, 12, 50, 51).takeWhileWithFirst((first, current) => (current - first) < 10)
   *   // Yields: 10, 11, 12
   * }}}
   */
  def takeWhileWithFirst(predicate: (Return, Return) => Boolean): Stream[Return] = {
    new Stream[Return](
      task.map { pull =>
        var firstOpt: Option[Return] = None
        var done = false
        () => {
          if (done) None
          else pull.pull() match {
            case None =>
              done = true
              None
            case Some(next) =>
              firstOpt match {
                case None =>
                  firstOpt = Some(next)
                  Some(next)
                case Some(first) =>
                  if (predicate(first, next)) Some(next)
                  else {
                    done = true
                    None
                  }
              }
          }
        }
      }
    )
  }

  /**
   * Transforms the values in the stream using the given function.
   *
   * @param f the function to transform the values
   * @tparam T the type of the transformed values
   * @return a new stream with the transformed values
   */
  def map[T](f: Return => T): Stream[T] = new Stream[T](
    task.map { pullR =>
      new Pull[T] {
        override def pull(): Option[T] = {
          pullR.pull().map(f)
        }
      }
    }
  )

  /**
   * Similar to map, but doesn't change the result. Allows doing something with each value without changing the result.
   *
   * @param f the function to handle each value
   * @return Stream[Return]
   */
  def foreach(f: Return => Unit): Stream[Return] = new Stream[Return](
    task.map { pullR =>
      new Pull[Return] {
        override def pull(): Option[Return] = {
          pullR.pull() match {
            case some @ Some(r) => 
              f(r)
              some
            case None => None
          }
        }
      }
    }
  )

  /**
   * Transforms the values in the stream to include the index of the value within the stream
   */
  def zipWithIndex: Stream[(Return, Int)] = new Stream[(Return, Int)](
    task.map { pullR =>
      var i = 0
      () => pullR.pull() match {
        case Some(r) =>
          val idx = i
          i += 1
          Some((r, idx))
        case None => None
      }
    }
  )

  /**
   * Similar to zipWithIndex, but first does a count on the stream and includes the total (Return, Index, Total).
   *
   * Note: this must evaluate the full stream to count
   */
  def zipWithIndexAndTotal: Stream[(Return, Int, Int)] = Stream.force {
    count.map { total =>
      zipWithIndex.map {
        case (r, index) => (r, index, total)
      }
    }
  }

  /**
   * Transforms the values in the stream using the given function that returns a new stream.
   *
   * @param f the function to transform the values into new streams
   * @tparam T the type of the values in the new streams
   * @return a new stream with the transformed values
   */
  def flatMap[T](f: Return => Stream[T]): Stream[T] = new Stream[T](
    task.map { pullR =>
      new Pull[T] {
        private var currentPull: Option[Pull[T]] = None

        override def pull(): Option[T] = {
          @annotation.tailrec
          def loop(): Option[T] = {
            currentPull match {
              case Some(innerPull) =>
                innerPull.pull() match {
                  case some @ Some(_) => some
                  case None =>
                    currentPull = None
                    loop()
                }
              case None =>
                pullR.pull() match {
                  case Some(r) =>
                    val innerStream = f(r)
                    val innerPull = innerStream.task.sync()
                    currentPull = Some(innerPull)
                    loop()
                  case None => None
                }
            }
          }

          loop()
        }
      }
    }
  )

  /**
   * Transforms the values in the stream using the given function that returns a task.
   *
   * @param f the function to transform the values into tasks
   * @tparam T the type of the values in the tasks
   * @return a new stream with the transformed values
   */
  def evalMap[T](f: Return => Task[T]): Stream[T] = new Stream[T](
    task.map { pullR =>
      new Pull[T] {
        override def pull(): Option[T] = {
          pullR.pull() match {
            case Some(r) => Some(f(r).sync())
            case None => None
          }
        }
      }
    }
  )

  /**
   * Transforms the values in the stream using the given function that returns a task option.
   *
   * @param f the function to transform the values into tasks of Option
   * @tparam T the type of the values in the tasks
   * @return a new stream with the transformed and flattened values
   */
  def evalFlatMap[T](f: Return => Task[Option[T]]): Stream[T] = evalMap(f).flatten

  def evalForge[R >: Return, T](forge: Forge[R, T]): Stream[T] = new Stream[T](
    task.map { pullR =>
      new Pull[T] {
        override def pull(): Option[T] = {
          pullR.pull() match {
            case Some(r) => Some(forge(r).sync())
            case None => None
          }
        }
      }
    }
  )

  /**
   * Similar to evalMap, but doesn't change the result. Allows doing something with each value without changing the
   * result.
   *
   * @param f the function to handle each value
   * @return Stream[Return]
   */
  def evalTap(f: Return => Task[Unit]): Stream[Return] = evalForeach(f)

  /**
   * Synonym for evalTap
   */
  def evalForeach(f: Return => Task[Unit]): Stream[Return] = new Stream[Return](
    task.map { pullR =>
      new Pull[Return] {
        override def pull(): Option[Return] = {
          pullR.pull() match {
            case some @ Some(r) => 
              f(r).sync()
              some
            case None => None
          }
        }
      }
    }
  )

  /**
   * Chunks the stream's values into vectors of size `size` (except possibly the last).
   *
   * @param chunkSize the maximum size of each chunk
   * @return a new stream where each element is a vector of `Return` values
   */
  def chunk(chunkSize: Int = 1024): Stream[Vector[Return]] =
    new Stream(task.map { pullR =>
      new Pull[Vector[Return]] {
        private val done = new AtomicBoolean(false)

        override def pull(): Option[Vector[Return]] = {
          if (done.get()) {
            None
          } else {
            val buf = Vector.newBuilder[Return]
            var i   = 0

            while (i < chunkSize) {
              pullR.pull() match {
                case Some(r) =>
                  buf += r
                  i += 1

                case None =>
                  // no more elements → mark done and break
                  done.set(true)
                  i = chunkSize
              }
            }

            val v = buf.result()
            if (v.isEmpty) {
              None
            } else {
              Some(v)
            }
          }
        }
      }
    })

  /**
   * Creates a grouping Stream expecting the group delineation is the natural sort order of the results.
   *
   * @param grouper the grouping function
   * @tparam G the group key
   */
  def groupSequential[G, R >: Return](grouper: R => G): Stream[Grouped[G, R]] =
    new Stream(task.map { pullR =>
      var pushedBack: Option[R] = None
      var done = false

      () => {
        if (done)
          None
        else {
          val firstOpt = if (pushedBack.isDefined) {
            val v = pushedBack
            pushedBack = None
            v
          } else {
            pullR.pull()
          }
          firstOpt match {
            case None =>
              done = true
              None
            case Some(first) =>
              val key = grouper(first)
              val buf = scala.collection.mutable.ListBuffer.empty[R]
              buf += first
              var keepGoing = true
              while (keepGoing) {
                val n = pullR.pull()
                if (n.isDefined) {
                  val v = n.get
                  if (grouper(v) == key) {
                    buf += v
                  } else {
                    pushedBack = Some(v)
                    keepGoing = false
                  }
                } else {
                  done = true
                  keepGoing = false
                }
              }
              Some(Grouped(key, buf.toList))
          }
        }
      }
    })

  /**
   * Groups at a separator
   *
   * @param separator returns true if this entry represents a separator
   */
  def group(separator: Return => Boolean): Stream[List[Return]] =
    new Stream(task.map { pullR =>
      var done = false

      new Pull[List[Return]] {
        override def pull(): Option[List[Return]] = {
          if (done) {
            None
          } else {
            val buf = scala.collection.mutable.ListBuffer.empty[Return]
            var boundaryReached = false

            while (!boundaryReached) {
              pullR.pull() match {
                case Some(r) if !separator(r) =>
                  buf += r
                case Some(_) =>
                  boundaryReached = true
                case None =>
                  done = true
                  boundaryReached = true
              }
            }

            if (buf.isEmpty) {
              if (done) {
                None
              } else {
                pull()
              }
            } else {
              Some(buf.toList)
            }
          }
        }
      }
    })

  def drop(n: Int): Stream[Return] = {
    val counter = new AtomicInteger(0)
    transform { r =>
      if (counter.getAndIncrement() < n) Step.Skip else Step.Emit(r)
    }
  }

  def dropWhile(p: Return => Boolean): Stream[Return] = {
    val dropping = new AtomicBoolean(true)
    transform { r =>
      if (dropping.get()) {
        if (p(r)) Step.Skip else {
          dropping.set(false)
          Step.Emit(r)
        }
      } else Step.Emit(r)
    }
  }

  def slice(from: Int, until: Int): Stream[Return] = {
    val index = new AtomicInteger(0)
    transform { r =>
      val i = index.getAndIncrement()
      if (i >= from && i < until) Step.Emit(r)
      else if (i >= until) Step.Stop
      else Step.Skip
    }
  }

  def sliding(size: Int, step: Int = 1): Stream[Vector[Return]] = {
    require(size > 0 && step > 0)
    new Stream(task.map { pullR =>
      val buf = new scala.collection.mutable.Queue[Return]()
      var eof = false

      new Pull[Vector[Return]] {
        override def pull(): Option[Vector[Return]] = {
          // top up the buffer to `size` unless we've seen EOF
          while (!eof && buf.size < size) {
            pullR.pull() match {
              case Some(r) => buf.enqueue(r)
              case None    => eof = true
            }
          }

          if (buf.isEmpty) {
            None
          } else {
            val out = buf.take(size).toVector
            var i = 0
            while (i < step && buf.nonEmpty) {
              buf.dequeue()
              i += 1
            }
            Some(out)
          }
        }
      }
    })
  }

  def find(p: Return => Boolean): Task[Option[Return]] = {
    task.map { pullR =>
      @tailrec def loop(): Option[Return] = pullR.pull() match {
        case Some(r) if p(r) => Some(r)
        case Some(_) => loop()
        case None => None
      }
      loop()
    }
  }

  def exists(p: Return => Boolean): Task[Boolean] = find(p).map(_.isDefined)

  def forall(p: Return => Boolean): Task[Boolean] =
    task.map { pullR =>
      @tailrec def loop(): Boolean = pullR.pull() match {
        case Some(r) if p(r) => loop()
        case Some(_) => false
        case None => true
      }
      loop()
    }

  def contains[T >: Return](elem: T): Task[Boolean] = exists(_ == elem)

  def scanLeft[T](initial: T)(f: (T, Return) => T): Stream[T] = {
    val acc = new AtomicReference[T](initial)
    transform { r =>
      val updated = f(acc.get(), r)
      acc.set(updated)
      Step.Emit(updated)
    }
  }

  def distinct: Stream[Return] = {
    val seen = mutable.Set.empty[Return]
    filter(seen.add)
  }

  def intersperse[T >: Return](separator: T): Stream[T] = {
    val first = new AtomicBoolean(true)
    transform { r =>
      if (first.getAndSet(false)) Step.Emit(r)
      else Step.Concat(Pull.fromList(List(separator, r)))
    }
  }

  def zip[T2](other: Stream[T2]): Stream[(Return, T2)] = {
    val zipped = task.flatMap { pullA =>
      other.task.map { pullB =>
        new Pull[(Return, T2)] {
          override def pull(): Option[(Return, T2)] =
            for {
              a <- pullA.pull()
              b <- pullB.pull()
            } yield (a, b)
        }
      }
    }
    new Stream(zipped)
  }

  def zipAll[T2, T >: Return](other: Stream[T2], thisElem: T, otherElem: T2): Stream[(T, T2)] = {
    val zipped = task.flatMap { pullA =>
      other.task.map { pullB =>
        new Pull[(T, T2)] {
          override def pull(): Option[(T, T2)] = {
            val a = pullA.pull().getOrElse(thisElem)
            val b = pullB.pull().getOrElse(otherElem)
            if (a == thisElem && b == otherElem) None else Some((a, b))
          }
        }
      }
    }
    new Stream(zipped)
  }

  def zipWith[T2, R](other: Stream[T2])(f: (Return, T2) => R): Stream[R] = {
    zip(other).map { case (a, b) => f(a, b) }
  }

  def partition(p: Return => Boolean): (Stream[Return], Stream[Return]) = {
    val left  = this.filter(p)
    val right = this.filterNot(p)
    (left, right)
  }

  def groupBy[K](f: Return => K): Task[Map[K, List[Return]]] =
    task.map { pullR =>
      val m = new scala.collection.mutable.HashMap[K, scala.collection.mutable.ListBuffer[Return]]
      var next = pullR.pull()
      while (next.isDefined) {
        val r = next.get
        val k = f(r)
        val buf = m.getOrElseUpdate(k, scala.collection.mutable.ListBuffer.empty[Return])
        buf += r
        next = pullR.pull()
      }
      // freeze to the requested shape
      m.iterator.map { case (k, buf) => k -> buf.toList }.toMap
    }

  /**
   * Appends another stream to this stream.
   *
   * @param that the stream to append
   * @tparam T the type of the values in the appended stream
   * @return a new stream with the values from both streams
   */
  def append[T >: Return](that: => Stream[T]): Stream[T] =
    new Stream[T](
      task.flatMap { pullR =>
        that.task.map { pullT =>
          () => pullR.pull().orElse(pullT.pull())
        }
      }
    )

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
  def drain: Task[Unit] = fold(())((_, _) => Task(()))

  /**
   * Cycles through all results but only returns the last element. Will error if the Stream is empty.
   */
  def last: Task[Return] = lastOption.flatMap {
    case Some(r) => Task(r)
    case None => Task.error(new NoSuchElementException("Stream.last on empty stream"))
  }

  /**
   * Cycles through all results but only returns the last element or None if the stream is empty.
   */
  def lastOption: Task[Option[Return]] = fold(Option.empty[Return])((_, r) => Task(Some(r)))

  /**
   * Folds through the stream returning the last value
   *
   * @param initial the initial T to start with
   * @param f the processing function
   * @tparam T the resulting type
   * @return Task[T]
   */
  def fold[T](initial: T)(f: (T, Return) => Task[T]): Task[T] =
    task.flatMap { pullR =>
      def foldRec(acc: T): Task[T] = {
        pullR.pull() match {
          case Some(r) => f(acc, r).flatMap(foldRec)
          case None => Task.pure(acc)
        }
      }
      foldRec(initial)
    }

  /**
   * Materializes the entire stream and applies a stateful, effectful transformation using a cursor that
   * retains access to previously emitted values.
   *
   * This method is useful when you need context-aware stream processing — such as looking back at earlier
   * elements, mutating history, or skipping output altogether — which requires buffering the full stream.
   *
   * The function `f` receives the next input element and the current cursor, and returns a new `Cursor`
   * wrapped in a `Task`. The cursor allows access to previous output elements via `previous(n)`,
   * and modification through `modifyPrevious`.
   *
   * Example usage:
   * {{{
   *   stream.materializedCursorEvalMap[R, T] { (next, cursor) =>
   *     if (cursor.previous(1).contains(next)) Task.pure(cursor) // skip duplicate
   *     else Task.pure(cursor.add(next))
   *   }
   * }}}
   *
   * @tparam R a supertype of the stream's element type, needed due to type variance
   * @tparam T the output type of the transformed stream
   * @param f a function taking the current element and cursor state, returning the updated cursor
   * @return a new Stream[T] representing the transformed and fully materialized stream
   */
  def materializedCursorEvalMap[R >: Return, T](f: (R, Cursor[R, T]) => Task[Cursor[R, T]],
                                                handleEnd: Cursor[R, T] => Task[Cursor[R, T]] = (c: Cursor[R, T]) => Task.pure(c)): Stream[T] = Stream.force {
    fold(Cursor[R, T](Vector.empty)) { (cursor, next) =>
      f(next, cursor)
    }.map { cursor =>
      Stream.force(handleEnd(cursor).map(c => Stream.emits(c.history)))
    }
  }


  /**
   * Reduces the elements of this stream using the given binary operator,
   * starting with the first element as the initial value and combining
   * sequentially with the rest.
   *
   * This is similar to `reduceLeft` on standard collections, but supports
   * effectful computation via `Task`. If the stream is empty, the returned
   * `Task` will fail with a `NoSuchElementException`.
   *
   * @param f the effectful binary operator to apply between elements
   * @tparam T the result type, which must be a supertype of `Return`
   * @return a `Task` producing the reduced value of the stream
   * @throws NoSuchElementException if the stream is empty
   */
  def reduce[T >: Return](f: (T, T) => Task[T]): Task[T] = fold[Option[T]](None) { (optAcc, r) =>
    Task.defer {
      optAcc match {
        case Some(acc) => f(acc, r).map(Some(_))
        case None => Task.pure(Some(r))
      }
    }
  }.flatMap {
    case Some(result) => Task.pure(result)
    case None => Task.error(new NoSuchElementException("Stream.reduce on empty stream"))
  }

  /**
   * Grabs only the first result from the stream.
   */
  def first: Task[Return] = firstOption.flatMap {
    case Some(r) => Task(r)
    case None => Task.error(new NoSuchElementException("Stream.first on empty stream"))
  }

  /**
   * Grabs only the first element or None if the stream is empty.
   */
  def firstOption: Task[Option[Return]] = task.map(_.pull())

  /**
   * Converts the stream to a list.
   *
   * @return a task that produces a list of the values in the stream
   */
  def toList: Task[List[Return]] = {
    task.map { pullR =>
      val builder = List.newBuilder[Return]
      var next = pullR.pull()
      while (next.isDefined) {
        builder += next.get
        next = pullR.pull()
      }
      builder.result()
    }
  }

  def toVector: Task[Vector[Return]] = {
    task.map { pullR =>
      val builder = Vector.newBuilder[Return]
      var next = pullR.pull()
      while (next.isDefined) {
        builder += next.get
        next = pullR.pull()
      }
      builder.result()
    }
  }

  /**
   * Counts the number of elements in the stream and fully evaluates it.
   *
   * @return a `Task[Int]` representing the total number of entries evaluated
   */
  def count: Task[Int] = task.map { pullR =>
    var count = 0
    while (pullR.pull().isDefined) {
      count += 1
    }
    count
  }

  def par[T, R >: Return](maxThreads: Int = ParallelStream.DefaultMaxThreads,
                          maxBuffer: Int = ParallelStream.DefaultMaxBuffer)
                         (forge: Forge[R, T]): ParallelStream[R, T] = ParallelStream(
    stream = this,
    forge = forge.map(Option.apply),
    maxThreads = maxThreads,
    maxBuffer = maxBuffer
  )

  /**
   * Executes the stream in parallel using a fixed number of threads, applying the given [[Forge]]
   * function to each element purely for side effects (i.e., `Unit` results are discarded).
   *
   * This method is optimized for high-speed, fire-and-forget processing where:
   * - Output ordering does not matter
   * - No results are collected
   * - All elements are processed via an effectful computation (`Forge[R, Unit]`)
   *
   * Work is pulled from the stream and dispatched to `threads` concurrent workers, each of which
   * continues processing until the stream is exhausted or an error occurs. If any task fails,
   * the first encountered exception is propagated after all workers shut down and future tasks are skipped.
   *
   * @param threads the number of concurrent workers to use
   * @param forge the effectful function to apply to each element
   * @tparam R the supertype of the stream's element type
   * @return a `Task[Unit]` that completes when all elements are processed, or fails on the first error
   */
  def parFast[R >: Return](threads: Int = ParallelStream.DefaultMaxThreads)
                          (forge: Forge[R, Unit]): Task[Unit] = Task.defer {
    val pull = task.sync()
    @volatile var throwable = Option.empty[Throwable]

    def puller: Task[Unit] = Task {
      @tailrec
      def recurse(): Unit = pull.pull() match {
        case _ if throwable.nonEmpty => // Stop
        case None => // Nothing to do
        case Some(r) =>
          forge(r).handleError { t =>
            throwable = Some(t)
            Task.unit
          }.sync()
          recurse()
      }

      recurse()
    }

    val tasks = (0 until threads).toList.map { _ =>
      puller
    }

    tasks.tasksPar.map { _ =>
      throwable.foreach(throw _)
    }
  }
}

object Stream {
  /**
   * Creates a stream with a Pull task
   */
  def apply[Return](pull: Task[Pull[Return]]): Stream[Return] = new Stream(pull)

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
  def emit[Return](value: Return): Stream[Return] = apply(Task(Pull.fromList(List(value))))

  /**
   * Creates an empty stream.
   *
   * @tparam Return the type of the values in the stream
   * @return a new empty stream
   */
  def empty[Return]: Stream[Return] = apply()

  /**
   * Creates a stream from a sequence of values.
   *
   * @param seq the sequence of values
   * @tparam Return the type of the values
   * @return a new stream that emits the values in the sequence
   */
  def emits[Return](seq: Seq[Return]): Stream[Return] = new Stream(Task(Pull.fromSeq(seq)))

  /**
   * Creates a stream from an iterator task.
   *
   * @param iterator the iterator task
   * @tparam Return the type of the values
   * @return a new stream that emits the values in the iterator
   */
  def fromIterator[Return](iterator: Task[Iterator[Return]]): Stream[Return] = apply(iterator.map(Pull.fromIterator))

  /**
   * Forces a Task[Stream] into Stream
   */
  def force[Return](stream: Task[Stream[Return]]): Stream[Return] = new Stream[Return](stream.flatMap(_.task))

  /**
   * Merges an Iterator of Streams together into one lazily loading Stream
   */
  def merge[Return](streams: Task[Pull[Stream[Return]]]): Stream[Return] =
    new Stream[Return](
      streams.map { outerPull =>
        val innerQueue = new ConcurrentLinkedQueue[Pull[Return]]()

        () => {
          @tailrec
          def loop(): Option[Return] = {
            val inner = innerQueue.poll()
            if (inner != null) {
              inner.pull() match {
                case some@Some(_) =>
                  innerQueue.offer(inner)
                  some
                case None =>
                  loop()
              }
            } else {
              outerPull.pull() match {
                case Some(stream) =>
                  val p = stream.task.sync()
                  innerQueue.offer(p)
                  loop()
                case None =>
                  None
              }
            }
          }

          loop()
        }
      }
    )

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
   * @param bufferSize the buffer size internally to use for the InputStream. Defaults to 1024.
   * @return a new stream that emits Bytes
   */
  def fromInputStream(input: Task[InputStream], bufferSize: Int = 1024): Stream[Byte] =
    new Stream[Byte](input.map { is =>
      val lock = new AnyRef
      val buf = new Array[Byte](bufferSize)
      var pos = 0
      var len = 0

      () => {
        lock.synchronized {
          if (pos >= len) {
            len = is.read(buf)
            pos = 0
          }
          if (len < 0) {
            None
          } else {
            val b = buf(pos)
            pos += 1
            Some(b)
          }
        }
      }
    })

  def task[Return](stream: Stream[Return]): Task[Pull[Return]] = stream.task
}
