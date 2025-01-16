package rapid.ops

import rapid.Task

import java.io.{BufferedOutputStream, File, FileOutputStream}
import java.nio.file.Path

case class ByteStreamOps(stream: rapid.Stream[Byte]) {
  def toFile(file: File): Task[Long] = Task(new BufferedOutputStream(new FileOutputStream(file))).flatMap { out =>
    stream.map { byte =>
      out.write(byte)
    }.count.map(_.toLong).guarantee(Task {
      out.flush()
      out.close()
    })
  }

  def toPath(path: Path): Task[Long] = toFile(path.toFile)
}
