package rapid

import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.mutable

trait StreamCompanion {
  private[rapid] val PureSkip: Task[Step[Nothing]] = Task.pure(Step.Skip)
  private[rapid] val PureStop: Task[Step[Nothing]] = Task.pure(Step.Stop)

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
  def unfoldStreamEval[S, A](seed: S)(next: S => Task[Option[(Stream[A], S)]]): Stream[A] = apply(
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
              stream.task.map { p =>
                currentPull = p
                currentPullInitialized = true
              }
          }
        }

      def pullNext(): Task[Step[A]] = Task.defer {
        lock.synchronized {
          if (done) {
            PureStop
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
                PureSkip
              case Step.Skip => PureSkip
              case Step.Concat(inner) =>
                Task.pure(Step.Concat(inner.asInstanceOf[Pull[Return]]): Step[Return])
              case Step.Stop =>
                if (innerQueue.nonEmpty) {
                  innerQueue.dequeue().map(p => Step.Concat(p): Step[Return])
                } else {
                  PureStop
                }
            }
          }
        }
        Pull(pullNext(), outerPull.close)
      }
    )
}
