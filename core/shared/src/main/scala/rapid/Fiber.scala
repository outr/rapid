package rapid

trait Fiber[Return] extends Any {
  def sync(): Return
}