package rapid.ops

import rapid.ParallelStream

case class OptionParallelStreamOps[T, Return](stream: ParallelStream[T, Option[Return]]) extends AnyVal {
  def flatten: ParallelStream[T, Return] = unNone
  def unNone: ParallelStream[T, Return] = stream.collect {
    case Some(r) => r
  }
}