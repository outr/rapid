package rapid

import _root_.cats.effect.IO

package object cats {
  implicit class TaskExtras[Return](val task: Task[Return]) extends AnyVal {
    def toIO: IO[Return] = IO.blocking(task.sync())
  }

  implicit class StreamExtras[Return](val stream: Stream[Return]) extends AnyVal {
    def toFS2: fs2.Stream[IO, Return] = {
      def loop(stream: Stream[Return]): fs2.Stream[IO, Return] = {
        fs2.Stream.eval(stream.pull.toTask.toIO).flatMap {
          case Some((head, tail)) => fs2.Stream.emit(head) ++ loop(tail)
          case None => fs2.Stream.empty
        }
      }
      loop(stream)
    }
  }
}