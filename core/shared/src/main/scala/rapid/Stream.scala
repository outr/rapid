package rapid

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicReference}
import scala.annotation.tailrec
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.{DurationInt, FiniteDuration}

/**
 * A lazy, pull-based stream abstraction for processing elements with full support for concurrency and composability.
 *
 * @tparam Return the type of the elements emitted by this stream
 */
class Stream[+Return](private val task: Task[Pull[Return]]) extends AnyVal {
  def transform[T](f: Return => Step[T]): Stream[T] =
    new Stream[T](task.map { outer =>
      lazy val mapStep: Task[Step[Return]] => Task[Step[T]] = { stepTask =>
        stepTask.map {
          case Step.Emit(a) => f(a)
          case Step.Skip => Step.Skip
          case Step.Stop => Step.Stop
          case Step.Concat(inner) => Step.Concat(inner.transform(mapStep))
        }
      }
      outer.transform(mapStep)
    })

  /**
   * Filters the values in the stream using the given predicate.
   *
   * @param p the predicate to test the values
   * @return a new stream with the values that satisfy the predicate
   */
  def filter(p: Return => Boolean): Stream[Return] =
    transform(a => if (p(a)) Step.Emit(a) else Step.Skip)

  def filterNot(p: Return => Boolean): Stream[Return] = filter(r => !p(r))

  /**
   * Builds a new stream by applying a partial function to all elements of this stream on which the function is defined.
   *
   * @param f the partial function to apply
   * @tparam T the new return type
   */
  def collect[T](f: PartialFunction[Return, T]): Stream[T] =
    transform(r => f.lift(r).map(Step.Emit(_)).getOrElse(Step.Skip))

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
   * @example
   * {{{
   *   Stream(10, 11, 12, 50, 51).takeWhileWithFirst((first, current) => (current - first) < 10)
   *   // Yields: 10, 11, 12
   * }}}
   */
  def takeWhileWithFirst(predicate: (Return, Return) => Boolean): Stream[Return] = {
    var firstOpt: Option[Return] = None
    var done = false
    transform { next =>
      if (done) {
        Step.Stop
      } else {
        firstOpt match {
          case None =>
            firstOpt = Some(next)
            Step.Emit(next)
          case Some(first) =>
            if (predicate(first, next)) {
              Step.Emit(next)
            } else {
              done = true
              Step.Stop
            }
        }
      }
    }
  }

  /**
   * Transforms the values in the stream using the given function.
   *
   * @param f the function to transform the values
   * @tparam T the type of the transformed values
   * @return a new stream with the transformed values
   */
  def map[T](f: Return => T): Stream[T] = transform(a => Step.Emit(f(a)))

  /**
   * Similar to map, but doesn't change the result. Allows doing something with each value without changing the result.
   *
   * @param f the function to handle each value
   * @return Stream[Return]
   */
  def foreach(f: Return => Unit): Stream[Return] = transform { a =>
    f(a)
    Step.Emit(a)
  }

  /**
   * Transforms the values in the stream to include the index of the value within the stream
   */
  def zipWithIndex: Stream[(Return, Int)] = {
    var i = 0
    transform { r =>
      val idx = i
      i += 1
      Step.Emit((r, idx))
    }
  }

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
  def flatMap[T](f: Return => Stream[T]): Stream[T] =
    new Stream[T](task.map { outer =>
      lazy val mapStep: Task[Step[Return]] => Task[Step[T]] = { stepTask =>
        stepTask.flatMap {
          case Step.Emit(a) => f(a).task.map(innerPull => Step.Concat(innerPull): Step[T])
          case Step.Skip => Task.pure(Step.Skip)
          case Step.Stop => Task.pure(Step.Stop)
          case Step.Concat(inner) => Task.pure(Step.Concat(inner.transform(mapStep)))
        }
      }
      outer.transform(mapStep)
    })

  /**
   * Transforms the values in the stream using the given function that returns a task.
   *
   * @param f the function to transform the values into tasks
   * @tparam T the type of the values in the tasks
   * @return a new stream with the transformed values
   */
  def evalMap[T](f: Return => Task[T]): Stream[T] =
    new Stream[T](task.map { outer =>
      lazy val mapStep: Task[Step[Return]] => Task[Step[T]] = { stepTask =>
        stepTask.flatMap {
          case Step.Emit(a) => f(a).map(v => Step.Emit(v): Step[T])
          case Step.Skip => Task.pure(Step.Skip)
          case Step.Stop => Task.pure(Step.Stop)
          case Step.Concat(inner) => Task.pure(Step.Concat(inner.transform(mapStep)))
        }
      }
      outer.transform(mapStep)
    })

  /**
   * Transforms the values in the stream using the given function that returns a task option.
   *
   * @param f the function to transform the values into tasks of Option
   * @tparam T the type of the values in the tasks
   * @return a new stream with the transformed and flattened values
   */
  def evalFlatMap[T](f: Return => Task[Option[T]]): Stream[T] = evalMap(f).flatten

  def evalForge[R >: Return, T](forge: Forge[R, T]): Stream[T] =
    new Stream[T](task.map { outer =>
      lazy val mapStep: Task[Step[Return]] => Task[Step[T]] = { stepTask =>
        stepTask.flatMap {
          case Step.Emit(a) => forge(a).map(v => Step.Emit(v): Step[T])
          case Step.Skip => Task.pure(Step.Skip)
          case Step.Stop => Task.pure(Step.Stop)
          case Step.Concat(inner) => Task.pure(Step.Concat(inner.transform(mapStep)))
        }
      }
      outer.transform(mapStep)
    })

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
  def evalForeach(f: Return => Task[Unit]): Stream[Return] =
    new Stream[Return](task.map { outer =>
      lazy val mapStep: Task[Step[Return]] => Task[Step[Return]] = { stepTask =>
        stepTask.flatMap {
          case Step.Emit(a) => f(a).map(_ => Step.Emit(a): Step[Return])
          case Step.Skip => Task.pure(Step.Skip)
          case Step.Stop => Task.pure(Step.Stop)
          case Step.Concat(inner) => Task.pure(Step.Concat(inner.transform(mapStep)))
        }
      }
      outer.transform(mapStep)
    })

  def every(duration: FiniteDuration, onlyOnChange: Boolean = false)
           (f: Return => Task[Unit]): Stream[Return] = {
    var keepAlive = true
    var current = Option.empty[Return]
    var previous = Option.empty[Return]
    new Stream[Return](task.map { pull =>
      val start = System.currentTimeMillis()
      Task.condition(Task.defer {
        val t = current match {
          case Some(r) if !onlyOnChange || current != previous =>
            previous = current
            f(r)
          case _ => Task.unit
        }
        t.pure(!keepAlive)
      }, delay = duration).start()
      pull.onClose(Task {
        keepAlive = false
      })
    }).map { r =>
      current = Some(r)
      r
    }
  }

  /**
   * Chunks the stream's values into vectors of size `size` (except possibly the last).
   *
   * @param chunkSize the maximum size of each chunk
   * @return a new stream where each element is a vector of `Return` values
   */
  def chunk(chunkSize: Int = 1024): Stream[Vector[Return]] = {
    require(chunkSize > 0)
    val buf = Vector.newBuilder[Return]
    var count = 0
    var flushed = false
    new Stream(task.map { pullR =>
      lazy val mapStep: Task[Step[Return]] => Task[Step[Vector[Return]]] = { st =>
        st.map {
          case Step.Emit(r) =>
            buf += r
            count += 1
            if (count >= chunkSize) {
              val out = buf.result()
              buf.clear()
              count = 0
              Step.Emit(out)
            } else {
              Step.Skip
            }
          case Step.Skip => Step.Skip
          case Step.Stop => Step.Stop
          case Step.Concat(inner) => Step.Concat(inner.transform(mapStep))
        }
      }
      val base = pullR.transform(mapStep)
      base.transform { st =>
        st.map {
          case Step.Stop if count > 0 && !flushed =>
            flushed = true
            val out = buf.result()
            buf.clear()
            count = 0
            Step.Emit(out)
          case s @ Step.Stop => s
          case e @ Step.Emit(_) => e
          case s @ Step.Skip => s
          case c @ Step.Concat(_) => c
        }
      }
    })
  }

  /**
   * Creates a grouping Stream expecting the group delineation is the natural sort order of the results.
   *
   * @param grouper the grouping function
   * @tparam G the group key
   */
  def groupSequential[G, R >: Return](grouper: R => G): Stream[Grouped[G, R]] = {
    Stream(task.map { pullR =>
      val buf = scala.collection.mutable.ListBuffer.empty[R]
      var curKey: Option[G] = None
      var flushed = false

      lazy val mapStep: Task[Step[Return]] => Task[Step[Grouped[G, R]]] = { st =>
        st.map {
          case Step.Emit(e) =>
            val r = e.asInstanceOf[R]
            val k = grouper(r)
            curKey match {
              case None =>
                curKey = Some(k)
                buf += r
                Step.Skip
              case Some(ck) if ck == k =>
                buf += r
                Step.Skip
              case Some(ck) =>
                val out = Grouped(ck, buf.toList)
                buf.clear()
                curKey = Some(k)
                buf += r
                Step.Emit(out)
            }
          case Step.Skip => Step.Skip
          case Step.Stop => Step.Stop
          case Step.Concat(inner) => Step.Concat(inner.transform(mapStep))
        }
      }

      val base = pullR.transform(mapStep)
      base.transform { st =>
        st.map {
          case Step.Stop if curKey.nonEmpty && buf.nonEmpty && !flushed =>
            flushed = true
            val out = Grouped(curKey.get, buf.toList)
            buf.clear()
            curKey = None
            Step.Emit(out)
          case s @ Step.Stop => s
          case e @ Step.Emit(_) => e
          case s @ Step.Skip => s
          case c @ Step.Concat(_) => c
        }
      }
    })
  }

  /**
   * Groups at a separator
   *
   * @param separator returns true if this entry represents a separator
   */
  def group(separator: Return => Boolean): Stream[List[Return]] =
    new Stream(task.map { pullR =>
      val buf = scala.collection.mutable.ListBuffer.empty[Return]
      def nextGroup(): Task[Step[List[Return]]] =
        pullR.pull.flatMap {
          case Step.Emit(r) if !separator(r) =>
            buf += r
            nextGroup()
          case Step.Emit(_) =>
            if (buf.nonEmpty) {
              val out = buf.toList
              buf.clear()
              Task.pure(Step.Emit(out))
            } else {
              nextGroup()
            }
          case Step.Skip => nextGroup()
          case Step.Concat(_) =>
            nextGroup()
          case Step.Stop =>
            if (buf.nonEmpty) {
              val out = buf.toList
              buf.clear()
              Task.pure(Step.Emit(out))
            } else {
              Task.pure(Step.Stop)
            }
        }
      Pull(nextGroup(), pullR.close)
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
        if (p(r)) {
          Step.Skip
        } else {
          dropping.set(false)
          Step.Emit(r)
        }
      } else {
        Step.Emit(r)
      }
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
      lazy val mapStep: Task[Step[Return]] => Task[Step[Vector[Return]]] = { st =>
        st.map {
          case Step.Emit(r) =>
            buf.enqueue(r)
            val outs = ListBuffer.empty[Vector[Return]]
            while (buf.size >= size) {
              outs += buf.take(size).toVector
              var i = 0
              while (i < step && buf.nonEmpty) {
                buf.dequeue()
                i += 1
              }
            }
            if (outs.isEmpty) Step.Skip
            else if (outs.size == 1) Step.Emit(outs.head)
            else Step.Concat(Pull.fromList(outs.toList))
          case Step.Skip => Step.Skip
          case Step.Stop =>
            val outs = ListBuffer.empty[Vector[Return]]
            while (buf.nonEmpty) {
              outs += buf.take(size).toVector
              var i = 0
              while (i < step && buf.nonEmpty) {
                buf.dequeue()
                i += 1
              }
            }
            if (outs.isEmpty) Step.Stop else Step.Concat(Pull.fromList(outs.toList))
          case Step.Concat(inner) => Step.Concat(inner.transform(mapStep))
        }
      }
      pullR.transform(mapStep)
    })
  }

  def guarantee(task: Task[Unit]): Stream[Return] = new Stream[Return](this.task.map { pull =>
    pull.onClose(task)
  })

  def find(p: Return => Boolean): Task[Option[Return]] =
    task.flatMap { pullR =>
      def loop(current: Pull[Return], stack: List[Pull[Return]]): Task[Option[Return]] =
        current.pull.flatMap {
          case Step.Emit(r) => if (p(r)) Task.pure(Some(r)) else loop(current, stack)
          case Step.Skip => loop(current, stack)
          case Step.Stop => stack match {
            case Nil => Task.pure(None)
            case hd :: tl => loop(hd, tl)
          }
          case Step.Concat(inner) => loop(inner, current :: stack)
        }
      loop(pullR, Nil).guarantee(pullR.close.handleError(_ => Task.unit))
    }

  def exists(p: Return => Boolean): Task[Boolean] = find(p).map(_.isDefined)

  def forall(p: Return => Boolean): Task[Boolean] =
    task.flatMap { pullR =>
      def loop(current: Pull[Return], stack: List[Pull[Return]]): Task[Boolean] =
        current.pull.flatMap {
          case Step.Emit(r) => if (!p(r)) Task.pure(false) else loop(current, stack)
          case Step.Skip => loop(current, stack)
          case Step.Stop => stack match {
            case Nil => Task.pure(true)
            case hd :: tl => loop(hd, tl)
          }
          case Step.Concat(inner) => loop(inner, current :: stack)
        }
      loop(pullR, Nil).guarantee(pullR.close.handleError(_ => Task.unit))
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

  def distinctOn[V](f: Return => V): Stream[Return] = {
    val seen = mutable.Set.empty[V]
    filter(r => seen.add(f(r)))
  }

  def intersperse[T >: Return](separator: T): Stream[T] = {
    val first = new AtomicBoolean(true)
    transform { r =>
      if (first.getAndSet(false)) Step.Emit(r)
      else Step.Concat(Pull.fromList(List(separator, r)))
    }
  }

  def zip[T2](other: Stream[T2]): Stream[(Return, T2)] =
    new Stream(task.flatMap { pullA =>
      other.task.map { pullB =>
        def nextVal[X](current: Pull[X], stack: List[Pull[X]]): Task[Option[(X, Pull[X], List[Pull[X]])]] =
          current.pull.flatMap {
            case Step.Emit(a) => Task.pure(Some((a, current, stack)))
            case Step.Skip => nextVal(current, stack)
            case Step.Stop => stack match {
              case Nil => Task.pure(None)
              case hd :: tl => nextVal(hd, tl)
            }
            case Step.Concat(inner) => nextVal(inner, current :: stack)
          }

        var curA: Pull[Return] = pullA
        var stackA: List[Pull[Return]] = Nil
        var curB: Pull[T2] = pullB
        var stackB: List[Pull[T2]] = Nil

        val pullTask: Task[Step[(Return, T2)]] =
          nextVal(curA, stackA).flatMap {
            case None => Task.pure(Step.Stop)
            case Some((a, newCurA, newStackA)) =>
              curA = newCurA
              stackA = newStackA
              nextVal(curB, stackB).map {
                case None => Step.Stop
                case Some((b, newCurB, newStackB)) =>
                  curB = newCurB
                  stackB = newStackB
                  Step.Emit((a, b))
              }
          }

        Pull(pullTask, pullA.close.flatMap(_ => pullB.close))
      }
    })

  def zipAll[T2, T >: Return](other: Stream[T2], thisElem: T, otherElem: T2): Stream[(T, T2)] =
    new Stream(task.flatMap { pullA =>
      other.task.map { pullB =>
        var aDone = false
        var bDone = false

        def nextA(current: Pull[T], stack: List[Pull[T]]): Task[Option[(T, Pull[T], List[Pull[T]])]] =
          if (aDone) Task.pure(None)
          else current.pull.flatMap {
            case Step.Emit(a) => Task.pure(Some((a, current, stack)))
            case Step.Skip => nextA(current, stack)
            case Step.Stop => stack match {
              case Nil =>
                aDone = true
                Task.pure(None)
              case hd :: tl => nextA(hd, tl)
            }
            case Step.Concat(inner) => nextA(inner, current :: stack)
          }

        def nextB(current: Pull[T2], stack: List[Pull[T2]]): Task[Option[(T2, Pull[T2], List[Pull[T2]])]] =
          if (bDone) Task.pure(None)
          else current.pull.flatMap {
            case Step.Emit(b) => Task.pure(Some((b, current, stack)))
            case Step.Skip => nextB(current, stack)
            case Step.Stop => stack match {
              case Nil =>
                bDone = true
                Task.pure(None)
              case hd :: tl => nextB(hd, tl)
            }
            case Step.Concat(inner) => nextB(inner, current :: stack)
          }

        var curA: Pull[T] = pullA.asInstanceOf[Pull[T]]
        var stackAV: List[Pull[T]] = Nil
        var curB: Pull[T2] = pullB
        var stackBV: List[Pull[T2]] = Nil

        val pullTask: Task[Step[(T, T2)]] =
          nextA(curA, stackAV).flatMap { optA =>
            val aVal = optA.map { case (v, c, s) => curA = c; stackAV = s; v }
              .orElse(if (!bDone) Some(thisElem) else None)
            nextB(curB, stackBV).map { optB =>
              val bVal = optB.map { case (v, c, s) => curB = c; stackBV = s; v }
                .orElse(if (!aDone) Some(otherElem) else None)
              (aVal, bVal) match {
                case (Some(aa), Some(bb)) => Step.Emit((aa, bb))
                case _ => Step.Stop
              }
            }
          }

        Pull(pullTask, pullA.close.flatMap(_ => pullB.close))
      }
    })

  def zipWith[T2, R](other: Stream[T2])(f: (Return, T2) => R): Stream[R] = {
    zip(other).map { case (a, b) => f(a, b) }
  }

  def partition(p: Return => Boolean): (Stream[Return], Stream[Return]) = {
    val left = this.filter(p)
    val right = this.filterNot(p)
    (left, right)
  }

  def groupBy[K](f: Return => K): Task[Map[K, List[Return]]] =
    fold(new scala.collection.mutable.HashMap[K, scala.collection.mutable.ListBuffer[Return]]) { (m, r) =>
      Task {
        val k = f(r)
        val buf = m.getOrElseUpdate(k, scala.collection.mutable.ListBuffer.empty[Return])
        buf += r
        m
      }
    }.map(_.iterator.map { case (k, buf) => k -> buf.toList }.toMap)

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
          var firstDone = false
          pullR.transform { stepTask =>
            stepTask.map {
              case Step.Emit(a) => Step.Emit(a)
              case Step.Skip => Step.Skip
              case Step.Concat(inner) => Step.Concat(inner)
              case Step.Stop =>
                if (!firstDone) {
                  firstDone = true
                  Step.Concat(pullT)
                } else {
                  Step.Stop
                }
            }
          }.onClose(pullR.close.flatMap(_ => pullT.close))
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

  /** Run `fin` exactly once when the stream terminates (success, empty) or is short-circuited by the consumer. */
  def onFinalize(fin: Task[Unit]): Stream[Return] = new Stream[Return](
    task.map { pullR =>
      val fired = new AtomicBoolean(false)
      pullR.transform { stepTask =>
        stepTask.flatMap {
          case Step.Stop if fired.compareAndSet(false, true) =>
            fin.handleError(_ => Task.unit).map(_ => Step.Stop: Step[Return])
          case other => Task.pure(other)
        }.handleError { t =>
          if (fired.compareAndSet(false, true)) {
            fin.handleError(_ => Task.unit).flatMap(_ => Task.error(t))
          } else {
            Task.error(t)
          }
        }
      }
    }
  )

  /** Run `fin(t)` if the pull throws; useful for logging/cleanup on error. */
  def onErrorFinalize(fin: Throwable => Task[Unit]): Stream[Return] = new Stream[Return](
    task.map { pullR =>
      pullR.transform { stepTask =>
        stepTask.handleError { t =>
          fin(t).handleError(_ => Task.unit).flatMap(_ => Task.error(t))
        }
      }
    }
  )

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
   * @param f       the processing function
   * @tparam T the resulting type
   * @return Task[T]
   */
  def fold[T](initial: T)(f: (T, Return) => Task[T]): Task[T] =
    task.flatMap { pullR =>
      def loop(current: Pull[Return], stack: List[Pull[Return]], acc: T): Task[T] =
        current.pull.flatMap {
          case Step.Emit(r) => f(acc, r).flatMap(newAcc => loop(current, stack, newAcc))
          case Step.Skip => loop(current, stack, acc)
          case Step.Stop => stack match {
            case Nil => Task.pure(acc)
            case hd :: tl => loop(hd, tl, acc)
          }
          case Step.Concat(inner) => loop(inner, current :: stack, acc)
        }
      loop(pullR, Nil, initial).guarantee(pullR.close.handleError(_ => Task.unit))
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
  def firstOption: Task[Option[Return]] =
    task.flatMap { pullR =>
      def loop(current: Pull[Return], stack: List[Pull[Return]]): Task[Option[Return]] =
        current.pull.flatMap {
          case Step.Emit(r) => Task.pure(Some(r))
          case Step.Skip => loop(current, stack)
          case Step.Stop => stack match {
            case Nil => Task.pure(None)
            case hd :: tl => loop(hd, tl)
          }
          case Step.Concat(inner) => loop(inner, current :: stack)
        }
      loop(pullR, Nil).guarantee(pullR.close.handleError(_ => Task.unit))
    }

  /**
   * Converts the stream to a list.
   *
   * @return a task that produces a list of the values in the stream
   */
  def toList: Task[List[Return]] =
    fold(List.newBuilder[Return])((builder, r) => Task.pure(builder += r)).map(_.result())

  def toVector: Task[Vector[Return]] =
    fold(Vector.newBuilder[Return])((builder, r) => Task.pure(builder += r)).map(_.result())

  /**
   * Counts the number of elements in the stream and fully evaluates it.
   *
   * @return a `Task[Int]` representing the total number of entries evaluated
   */
  def count: Task[Int] = fold(0)((cnt, _) => Task.pure(cnt + 1))

  def par[T, R >: Return](maxThreads: Int = ParallelStream.DefaultMaxThreads,
                          maxBuffer: Int = ParallelStream.DefaultMaxBuffer)
                         (forge: Forge[R, T]): ParallelStream[R, T] = {
    val threads = if (maxThreads <= 0) 1 else maxThreads
    val buffer  = if (maxBuffer <= 0) 1 else maxBuffer
    ParallelStream(
      stream = this,
      forge = forge.map(Option.apply),
      maxThreads = threads,
      maxBuffer = buffer
    )
  }

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
   * @param forge   the effectful function to apply to each element
   * @tparam R the supertype of the stream's element type
   * @return a `Task[Unit]` that completes when all elements are processed, or fails on the first error
   */
  def parForeach[R >: Return](threads: Int = ParallelStream.DefaultMaxThreads)
                             (forge: Forge[R, Unit]): Task[Unit] =
    task.flatMap { pull =>
      @volatile var throwable = Option.empty[Throwable]

      def recurse(current: Pull[R], stack: List[Pull[R]]): Task[Unit] = {
        if (throwable.nonEmpty) Task.unit
        else current.pull.flatMap {
          case Step.Emit(e) =>
            forge(e.asInstanceOf[R]).handleError { t =>
              throwable = Some(t)
              Task.unit
            }.flatMap(_ => recurse(current, stack))
          case Step.Skip => recurse(current, stack)
          case Step.Stop => stack match {
            case Nil => Task.unit
            case hd :: tl => recurse(hd, tl)
          }
          case Step.Concat(inner) => recurse(inner.asInstanceOf[Pull[R]], current :: stack)
        }
      }

      val safeThreads = if (threads <= 0) 1 else threads
      val tasks = (0 until safeThreads).toList.map { _ =>
        recurse(pull.asInstanceOf[Pull[R]], Nil)
      }

      tasks.tasksPar.map { _ =>
        throwable.foreach(throw _)
      }.guarantee(pull.close.handleError(_ => Task.unit))
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

  /** Bracketed acquisition/usage/release for resources that back a Stream. */
  def using[R, A](acquire: Task[R])(use: R => Stream[A])(release: R => Task[Unit]): Stream[A] =
    new Stream[A](
      acquire.flatMap { r =>
        val released = new AtomicBoolean(false)
        def ensureRelease(): Task[Unit] = Task.defer {
          if (released.compareAndSet(false, true)) release(r).handleError(_ => Task.unit)
          else Task.unit
        }

        use(r).task.map { pull =>
          pull.transform { stepTask =>
            stepTask.flatMap {
              case Step.Stop => ensureRelease().map(_ => Step.Stop: Step[A])
              case other => Task.pure(other)
            }.handleError { t =>
              ensureRelease().flatMap(_ => Task.error(t))
            }
          }.onClose(ensureRelease())
        }
      }
    )

  /**
   * Safely unfold a Stream-of-Streams without building recursive `append` / `Concat` chains.
   */
  def unfoldStreamEval[S, A](seed: S)(next: S => Task[Option[(Stream[A], S)]]): Stream[A] = Stream(
    Task.defer {
      val lock = new AnyRef

      var state: S = seed
      var currentPull: Pull[A] = Pull.fromList(Nil)
      var currentPullInitialized: Boolean = false
      var done: Boolean = false

      def closeCurrentPull(): Task[Unit] =
        currentPull.close.attempt.map(_ => ())

      def fetchNextPull(): Task[Unit] =
        closeCurrentPull().flatMap { _ =>
          next(state).flatMap {
            case None =>
              done = true
              currentPull = Pull.fromList(Nil)
              currentPullInitialized = true
              Task.unit
            case Some((stream, nextState)) =>
              state = nextState
              Stream.task(stream).map { p =>
                currentPull = p
                currentPullInitialized = true
              }
          }
        }

      def pullNext(): Task[Step[A]] = Task.defer {
        lock.synchronized {
          if (done) {
            Task.pure(Step.Stop)
          } else if (!currentPullInitialized) {
            fetchNextPull().flatMap(_ => pullNext())
          } else {
            currentPull.pull.flatMap {
              case e @ Step.Emit(_) => Task.pure(e)
              case Step.Skip => pullNext()
              case Step.Concat(inner) =>
                currentPull = inner
                pullNext()
              case Step.Stop =>
                currentPullInitialized = false
                pullNext()
            }
          }
        }
      }

      val closeTask: Task[Unit] = Task.defer {
        lock.synchronized {
          done = true
          closeCurrentPull()
        }
      }

      Task.pure(Pull(pullNext(), closeTask))
    }
  )

  /** Managed-from-iterator with explicit release hook (always runs on termination/error). */
  def fromIteratorManaged[A](mk: Task[Iterator[A]])(release: Iterator[A] => Task[Unit]): Stream[A] = {
    using(mk) { it =>
      apply(Task.pure(Pull.fromIterator(it)))
    }(release)
  }

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
  def fromIterator[Return](iterator: Task[Iterator[Return]]): Stream[Return] = fromIteratorManaged(iterator) {
    case ac: AutoCloseable => Task(ac.close())
    case _ => Task.unit
  }

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
        val innerQueue = new mutable.Queue[Task[Pull[Return]]]()
        def pullNext(): Task[Step[Return]] = {
          if (innerQueue.nonEmpty) {
            innerQueue.dequeue().map(p => Step.Concat(p): Step[Return])
          } else {
            outerPull.pull.flatMap {
              case Step.Emit(stream) =>
                innerQueue.enqueue(stream.task)
                Task.pure(Step.Skip: Step[Return])
              case Step.Skip => Task.pure(Step.Skip: Step[Return])
              case Step.Concat(inner) =>
                Task.pure(Step.Concat(inner.asInstanceOf[Pull[Return]]): Step[Return])
              case Step.Stop =>
                if (innerQueue.nonEmpty) {
                  innerQueue.dequeue().map(p => Step.Concat(p): Step[Return])
                } else {
                  Task.pure(Step.Stop: Step[Return])
                }
            }
          }
        }
        Pull(pullNext(), outerPull.close)
      }
    )

  def task[Return](stream: Stream[Return]): Task[Pull[Return]] = stream.task
}
