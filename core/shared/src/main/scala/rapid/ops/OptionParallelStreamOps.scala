package rapid.ops

import rapid.ParallelStream

case class OptionParallelStreamOps[T, Return](stream: ParallelStream[T, Option[Return]]) {
  def unNone: ParallelStream[T, Return] = stream.collect {
    case Some(r) => r
  }
}