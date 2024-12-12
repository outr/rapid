package rapid

import scala.collection.BuildFrom

case class TaskSeqOps[Return, C[_]](seq: C[Task[Return]]) extends AnyVal {
  def tasks(implicit bf: BuildFrom[C[Task[Return]], Return, C[Return]],
                     asIterable: C[Task[Return]] => Iterable[Task[Return]]): Task[C[Return]] =
    Task.sequence(seq)
}