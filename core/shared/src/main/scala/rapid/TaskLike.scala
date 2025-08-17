package rapid

trait TaskLike[+A] {
  def start: TaskLike[A]
  def await(): A
  def sync(): A
}