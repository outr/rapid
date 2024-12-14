package rapid

import scala.concurrent.duration.FiniteDuration
import scala.language.implicitConversions

trait RapidPackage {
  implicit def taskSeq2Ops[Return, C[_]](tasks: C[Task[Return]]): TaskSeqOps[Return, C] = TaskSeqOps(tasks)

  implicit class BlockableTask[Return](task: Task[Return])
                                      (implicit fiber2Blockable: Fiber[Return] => Blockable[Return]) extends Blockable[Return] {
    override def await(): Return = task.start().await()

    override def await(duration: FiniteDuration): Option[Return] = task.start().await(duration)
  }
}
