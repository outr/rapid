package rapid.ops

import rapid.Stream

case class OptionStreamOps[Return](stream: Stream[Option[Return]]) {
  def flatten: Stream[Return] = unNone
  def unNone: Stream[Return] = stream.collect {
    case Some(r) => r
  }
}