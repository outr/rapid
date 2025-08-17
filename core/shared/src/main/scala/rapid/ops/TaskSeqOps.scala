package rapid.ops

import rapid.Task
import scala.collection.BuildFrom

/** Extra ops for collections of Task[_]. */
final case class TaskSeqOps[Return, C[X] <: IterableOnce[X]](seq: C[Task[Return]]) extends AnyVal {

  /** Sequence tasks, preserving the original collection shape C[_]. */
  def tasks(implicit
    bf: BuildFrom[C[Task[Return]], Return, C[Return]]
  ): Task[C[Return]] =
    Task.sequence(seq).map { xs =>
      val b = bf.newBuilder(seq)
      b ++= xs
      b.result()
    }

  /** Parallel sequence, preserving the original collection shape C[_]. */
  def tasksPar(implicit
    bf: BuildFrom[C[Task[Return]], Return, C[Return]]
  ): Task[C[Return]] =
    Task.parSequence(seq).map { xs =>
      val b = bf.newBuilder(seq)
      b ++= xs
      b.result()
    }
}
