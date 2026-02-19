package spec

import rapid._

import java.io.File
import java.nio.file.Files
import scala.concurrent.duration.DurationInt

class StreamJvmSpec extends AbstractBlockingStreamSpec {
  "Stream (JVM-only)" should {
    "do something every N millis" in {
      var checks = List.empty[Int]
      Stream
        .emits(1 to 100)
        .evalMap(i => Task.sleep(10.millis).map(_ => i))
        .every(100.millis) { i =>
          checks = i :: checks
          Task.unit
        }
        .drain
        .sync()
      checks should have size 10
    }
    "close underlying InputStream in fromInputStream" in {
      var closed = false
      val is = new java.io.ByteArrayInputStream("abc".getBytes("UTF-8")) {
        override def close(): Unit = {
          closed = true
          super.close()
        }
      }
      val out = StreamIO.fromInputStream(Task.pure(is)).toList.sync()
      out.map(_.toChar).mkString shouldEqual "abc"
      closed shouldBe true
    }
    "write a String to a File via byte stream" in {
      val stream = Stream.emits("Hello, World!".getBytes("UTF-8").toList)
      val file = new File("test.txt")
      val bytes = stream.toFile(file).sync()
      bytes should be(13)
      Files.readString(file.toPath) should be("Hello, World!")
      file.delete()
    }
  }
}
