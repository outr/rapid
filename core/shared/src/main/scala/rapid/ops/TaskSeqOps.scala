package rapid.ops

import rapid.Task

import scala.collection.BuildFrom
import scala.reflect.ClassTag

final case class TaskSeqOps[Return, C[_]](seq: C[Task[Return]]) extends AnyVal {
  def tasks(implicit bf: BuildFrom[C[Task[Return]], Return, C[Return]],
                     asIterable: C[Task[Return]] => Iterable[Task[Return]]): Task[C[Return]] =
    Task.sequence(seq)

  def tasksPar(implicit bf: BuildFrom[C[Task[Return]], Return, C[Return]],
                        asIterable: C[Task[Return]] => Iterable[Task[Return]],
                        ct: ClassTag[Return]): Task[C[Return]] =
    Task.parSequence(seq)

  def tasksParBounded(parallelism: Int)
                     (implicit bf: BuildFrom[C[Task[Return]], Return, C[Return]],
                      asIterable: C[Task[Return]] => Iterable[Task[Return]],
                      ct: ClassTag[Return]): Task[C[Return]] =
    Task.parSequenceBounded(seq, parallelism)
}