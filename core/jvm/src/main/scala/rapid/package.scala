import java.io.{BufferedOutputStream, File, FileOutputStream}
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue

import scala.language.implicitConversions

package object rapid extends RapidPackage {
  implicit class StreamJvmOps[Return](private val stream: Stream[Return]) {
    def parFold[T](initial: T,
                   threads: Int = ParallelStream.DefaultMaxThreads)
                  (f: (T, Return) => Task[T], merge: (T, T) => T): Task[T] = Task.defer {
      val cells = new ConcurrentLinkedQueue[Holder[T]]()
      val local = new ThreadLocal[Holder[T]] {
        override def initialValue(): Holder[T] = {
          val c = new Holder[T](initial)
          cells.add(c)
          c
        }
      }

      stream.parForeach(threads) { r =>
        Task {
          val c = local.get()
          c.value = f(c.value, r).sync()
        }
      }.map { _ =>
        var acc = initial
        val it = cells.iterator()
        while (it.hasNext) {
          acc = merge(acc, it.next().value)
        }
        acc
      }
    }
  }

  implicit class ByteStreamJvmOps(private val stream: Stream[Byte]) {
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
}
