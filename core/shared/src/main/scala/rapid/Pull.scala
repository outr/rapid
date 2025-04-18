package rapid

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import scala.jdk.CollectionConverters._

/** A simple, thread‑safe source of values of type T. */
trait Pull[+T] {
  /**
   * Atomically pull the next element, if any.
   * @return Some(next) if available, or None if exhausted.
   */
  def pull(): Option[T]
}

object Pull {
  /**
   * Wraps an Iterable in a lock‑free, thread‑safe Pull[T].
   * Under the hood it enqueues all elements and then atomically polls.
   */
  def fromIterable[T](iterable: Iterable[T]): Pull[T] = {
    val queue = new ConcurrentLinkedQueue[T](iterable.asJavaCollection)
    () => Option(queue.poll())
  }

  def fromList[T](list: List[T]): Pull[T] = {
    val state = new AtomicReference[List[T]](list)
    () => {
      @annotation.tailrec
      def loop(): Option[T] = {
        val current = state.get()
        current match {
          case h :: t =>
            if (state.compareAndSet(current, t)) Some(h)
            else loop()
          case Nil =>
            None
        }
      }

      loop()
    }
  }

  def fromIndexedSeq[T](seq: IndexedSeq[T]): Pull[T] = {
    val idx = new AtomicInteger(0)
    () => seq.lift(idx.getAndIncrement())
  }

  def fromSeq[T](seq: Seq[T]): Pull[T] = seq match {
    case list: List[T] => fromList(list)
    case indexed: IndexedSeq[T] => fromIndexedSeq(indexed)
    case _ => fromIndexedSeq(seq.toIndexedSeq)
  }

  def fromIterator[T](iter: Iterator[T]): Pull[T] = {
    val lock = new AnyRef
    () => {
      lock.synchronized {
        if (iter.hasNext) {
          Some(iter.next())
        } else {
          None
        }
      }
    }
  }
}