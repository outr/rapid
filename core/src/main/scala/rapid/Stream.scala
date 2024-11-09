package rapid

import java.util.concurrent.Semaphore

trait Stream[A] {
  def pull: Pull[Option[(A, Stream[A])]]

  def map[B](f: A => B): Stream[B] = new Stream[B] {
    def pull: Pull[Option[(B, Stream[B])]] = Stream.this.pull.flatMap {
      case Some((head, tail)) => Pull.output(Some(f(head) -> tail.map(f)))
      case None => Pull.output(None)
    }
  }

  def flatMap[B](f: A => Stream[B]): Stream[B] = new Stream[B] {
    def pull: Pull[Option[(B, Stream[B])]] = Stream.this.pull.flatMap {
      case Some((head, tail)) => f(head).append(tail.flatMap(f)).pull
      case None => Pull.output(None)
    }
  }

  def append[B >: A](that: => Stream[B]): Stream[B] = new Stream[B] {
    def pull: Pull[Option[(B, Stream[B])]] = Stream.this.pull.flatMap {
      case Some((head, tail)) => Pull.output(Some(head -> tail.append(that)))
      case None => that.pull
    }
  }

  def takeWhile(p: A => Boolean): Stream[A] = new Stream[A] {
    def pull: Pull[Option[(A, Stream[A])]] = Stream.this.pull.flatMap {
      case Some((head, tail)) =>
        if (p(head)) Pull.output(Some(head -> tail.takeWhile(p)))
        else Pull.output(None)
      case None => Pull.output(None)
    }
  }

  def filter(p: A => Boolean): Stream[A] = new Stream[A] {
    def pull: Pull[Option[(A, Stream[A])]] = Stream.this.pull.flatMap {
      case Some((head, tail)) =>
        if (p(head)) Pull.output(Some(head -> tail.filter(p)))
        else tail.filter(p).pull
      case None => Pull.output(None)
    }
  }

  def evalMap[B](f: A => Task[B]): Stream[B] = new Stream[B] {
    def pull: Pull[Option[(B, Stream[B])]] = Stream.this.pull.flatMap {
      case Some((head, tail)) =>
        Pull.suspend {
          f(head).map(result => Option(result -> tail.evalMap(f))).toPull
        }
      case None => Pull.output(None)
    }
  }

  def parEvalMap[B](maxConcurrency: Int)(f: A => Task[B]): Stream[B] = new Stream[B] {
    val semaphore = new Semaphore(maxConcurrency)

    def pull: Pull[Option[(B, Stream[B])]] = Pull.suspend {
      if (semaphore.tryAcquire()) {
        Stream.this.pull.flatMap {
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
            Pull.output(None)
        }
      } else {
        Pull.suspend(pull)
      }
    }
  }

  def toList: Task[List[A]] = {
    def loop(stream: Stream[A], acc: List[A]): Pull[List[A]] = {
      stream.pull.flatMap {
        case Some((head, tail)) => Pull.suspend(loop(tail, acc :+ head))
        case None => Pull.output(acc)
      }
    }
    loop(this, List.empty).toTask
  }
}

object Stream {
  def emit[A](value: A): Stream[A] = new Stream[A] {
    def pull: Pull[Option[(A, Stream[A])]] = Pull.output(Some(value -> empty))
  }

  def empty[A]: Stream[A] = new Stream[A] {
    def pull: Pull[Option[(A, Stream[A])]] = Pull.output(None)
  }

  def fromList[A](list: List[A]): Stream[A] = list match {
    case Nil => empty
    case head :: tail => Stream.emit(head).append(fromList(tail))
  }
}