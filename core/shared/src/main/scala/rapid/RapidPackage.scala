package rapid

import rapid.ops.{OptionParallelStreamOps, OptionStreamOps, TaskSeqOps, TaskTaskOps}

import scala.language.implicitConversions

trait RapidPackage {
  implicit def taskSeq2Ops[Return, C[_]](tasks: C[Task[Return]]): TaskSeqOps[Return, C] = TaskSeqOps(tasks)
  implicit def optionStream[Return](stream: Stream[Option[Return]]): OptionStreamOps[Return] = OptionStreamOps(stream)
  implicit def optionParallelStream[T, Return](stream: ParallelStream[T, Option[Return]]): OptionParallelStreamOps[T, Return] = OptionParallelStreamOps(stream)
  implicit def taskTaskOps[Return](task: Task[Task[Return]]): TaskTaskOps[Return] = TaskTaskOps(task)
}
