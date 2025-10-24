package rapid

import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

/** A simple, thread‑safe source of values of type T. */
case class Pull[+Return](pull: Task[Step[Return]], close: Task[Unit] = Task.unit) {
  def transform[T](f: Task[Step[Return]] => Task[Step[T]]): Pull[T] = copy[T](f(pull))

  def onClose(task: Task[Unit]): Pull[Return] = copy(close = close.effect(task))
}

object Pull {
  def fromFunction[T](pullF: () => Step[T], close: Task[Unit] = Task.unit): Pull[T] =
    Pull(Task {
      pullF()
    }, close)
  def fromList[T](list: List[T]): Pull[T] = {
    val state = new AtomicReference[List[T]](list)
    val pull = Task {
      @annotation.tailrec
      def loop(): Step[T] = {
        val current = state.get()
        if (current.nonEmpty) {
          val head = current.head
          val tail = current.tail
          if (state.compareAndSet(current, tail)) {
            Step.Emit(head)
          } else {
            loop()
          }
        } else {
          Step.Stop
        }
      }
      loop()
    }
    Pull(pull)
  }

  def fromIndexedSeq[T](seq: IndexedSeq[T]): Pull[T] = {
    val idx = new AtomicInteger(0)
    val len = seq.length
    val pull = Task {
      val i = idx.getAndIncrement()
      if (i < len) {
        val v = seq(i)
        Step.Emit(v)
      } else {
        Step.Stop
      }
    }
    Pull(pull)
  }

  def fromSeq[T](seq: Seq[T]): Pull[T] = seq match {
    case list: List[T] => fromList(list)
    case indexed: IndexedSeq[T] => fromIndexedSeq(indexed)
    case _ => fromIndexedSeq(seq.toIndexedSeq)
  }

  def fromIterator[T](iter: Iterator[T]): Pull[T] = {
    val lock = new AnyRef
    val pull = Task {
      lock.synchronized {
        try {
          if (iter.hasNext) {
            val v = iter.next()
            Step.Emit(v)
          } else {
            Step.Stop
          }
        } catch {
          case _: java.util.concurrent.RejectedExecutionException =>
            // Underlying iterator's executor was closed; treat as end-of-stream
            Step.Stop
        }
      }
    }
    Pull(pull)
  }
}