package rapid.v2

trait Fiber[Return] extends Any {
  def sync(): Return
}
