package rapid.ops

case class ByteStreamOps(stream: rapid.Stream[Byte]) {
  def chars: rapid.Stream[Char] = stream.map(_.toChar)

  def lines: rapid.Stream[String] = CharStreamOps(chars).lines
}
