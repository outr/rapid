package rapid.ops

import rapid.Task

case class IntStreamOps(stream: rapid.Stream[Int]) {
  def sum: Task[Int] = stream.fold(0)((value, total) => Task.pure(total + value))
}
