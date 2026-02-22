package rapid

import java.io.{BufferedInputStream, File, FileInputStream, InputStream}
import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.AtomicBoolean
import scala.jdk.CollectionConverters.IteratorHasAsScala

trait StreamPlatformCompanion { self: StreamCompanion =>
  def fromPath(path: Path): Stream[Byte] = fromFile(path.toFile)

  def listDirectory(directory: Path): Stream[Path] = force(Task {
    val ds = Files.newDirectoryStream(directory)
    val closed = new AtomicBoolean(false)

    def closeOnce(): Unit = if (closed.compareAndSet(false, true)) ds.close()

    val baseIt = ds.iterator().asScala

    val it: Iterator[Path] = new Iterator[Path] {
      override def hasNext: Boolean = {
        val hn = baseIt.hasNext
        if (!hn) closeOnce()
        hn
      }

      override def next(): Path = baseIt.next()
    }

    fromIterator(Task.pure(it))
      .onFinalize(Task(closeOnce()))
  })

  def listDirectory[B](directory: Path, sortBy: Path => B)
                       (implicit ordering: Ordering[B]): Stream[Path] = force(Task {
    val ds = Files.newDirectoryStream(directory)
    try {
      val list = ds.iterator().asScala.toList.sortBy(sortBy)(ordering)
      emits(list)
    } finally {
      ds.close()
    }
  })

  def fromFile(file: File): Stream[Byte] = fromInputStream(Task(new BufferedInputStream(new FileInputStream(file))))

  def fromInputStream(input: Task[InputStream], bufferSize: Int = 1024): Stream[Byte] =
    new Stream[Byte](input.map { is =>
      val lock = new AnyRef
      val buf = new Array[Byte](bufferSize)
      var pos = 0
      var len = 0

      Pull.fromFunction[Byte]({ () =>
        lock.synchronized {
          if (pos >= len) {
            len = is.read(buf)
            pos = 0
          }
          if (len < 0) {
            Step.Stop
          } else {
            val b = buf(pos)
            pos += 1
            Step.Emit(b)
          }
        }
      }, Task {
        try is.close()
        catch {
          case _: Throwable => ()
        }
      })
    })
}
