package rapid

import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

/** A simple, thread‑safe source of values of type T. */
trait Pull[+T] {
  /**
   * Atomically pull the next element, if any.
   * @return Some(next) if available, or None if exhausted.
   */
  def pull(): Option[T]

  def close: Task[Unit] = Task.unit
}

object Pull {
  def fromList[T](list: List[T]): Pull[T] = {
    val state = new AtomicReference[List[T]](list)
    () => {
      @annotation.tailrec
      def loop(): Option[T] = {
        val current = state.get()
        if (current.nonEmpty) {
          val head = current.head
          val tail = current.tail
          if (state.compareAndSet(current, tail)) Some(head)
          else loop()
        } else {
          None
        }
      }
      loop()
    }
  }

  def fromIndexedSeq[T](seq: IndexedSeq[T]): Pull[T] = {
    val idx = new AtomicInteger(0)
    val len = seq.length
    () => {
      val i = idx.getAndIncrement()
      if (i < len) Some(seq(i)) else None
    }
  }

  def fromSeq[T](seq: Seq[T]): Pull[T] = seq match {
    case list: List[T] => fromList(list)
    case indexed: IndexedSeq[T] => fromIndexedSeq(indexed)
    case _ => fromIndexedSeq(seq.toIndexedSeq)
  }

  def fromIterator[T](iter: Iterator[T]): Pull[T] = {
    val lock = new AnyRef
    () => lock.synchronized {
      if (iter.hasNext) Some(iter.next()) else None
    }
  }
}