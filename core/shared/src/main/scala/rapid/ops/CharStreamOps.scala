package rapid.ops

case class CharStreamOps(stream: rapid.Stream[Char]) {
  def lines: rapid.Stream[String] = stream
    .group(_ == '\n')
    .map(_.mkString.trim)
}
