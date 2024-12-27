package rapid.ops

import rapid.Stream

case class OptionStreamOps[Return](stream: Stream[Option[Return]]) {
  def unNone: Stream[Return] = stream.collect {
    case Some(r) => r
  }
}