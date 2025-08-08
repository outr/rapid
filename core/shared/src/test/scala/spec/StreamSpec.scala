package spec

import org.scalatest.concurrent.TimeLimitedTests
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Minute, Span}
import rapid._

import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.duration.DurationInt

class StreamSpec extends AnyWordSpec with Matchers with TimeLimitedTests {
  override def timeLimit: Span = Span(1, Minute)

  "Stream" should {
    "correctly map elements" in {
      val stream = Stream.emits(List(1, 2, 3, 4))
      val result = stream.map(_ * 2).toList.sync()
      result shouldEqual List(2, 4, 6, 8)
    }
    "correctly flatMap elements" in {
      val stream = Stream.emits(List(1, 2, 3))
      val result = stream.flatMap(x => Stream.emits(List(x, x * 2))).toList.sync()
      result shouldEqual List(1, 2, 2, 4, 3, 6)
    }
    "filter elements based on a predicate" in {
      val stream = Stream.emits(List(1, 2, 3, 4, 5))
      val result = stream.filter(_ % 2 == 0).toList.sync()
      result shouldEqual List(2, 4)
    }
    "take elements while a condition holds" in {
      val stream = Stream.emits(List(1, 2, 3, 4, 5))
      val result = stream.takeWhile(_ < 4).toList.sync()
      result shouldEqual List(1, 2, 3)
    }
    "evaluate elements sequentially with evalMap" in {
      val stream = Stream.emits(List(1, 2, 3))
      val result = stream.evalMap(x => Task(x * 2)).toList.sync()
      result shouldEqual List(2, 4, 6)
    }
    "chunk a stream with a few items" in {
      val chunks = Stream.emits(0 until 23).chunk(10).toList.sync()
      chunks should be(List(Vector(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), Vector(10, 11, 12, 13, 14, 15, 16, 17, 18, 19), Vector(20, 21, 22)))
    }
    "chunk a stream with no items" in {
      val chunks = Stream[Int]().chunk(10).evalMap { v => Task(v.map(_ * 100)) }.toList.sync()
      chunks should be(Nil)
    }
    "evaluate elements in parallel with par" in {
      val stream = Stream.emits(List(1, 2, 3, 4))
      val result = stream.par(2)(x => Task(x * 2)).toList.sync()
      result.sorted shouldEqual List(2, 4, 6, 8) // Sorting to account for parallel execution order
    }
    "limit parallelism with par" in {
      val stream = Stream.emits(List(1, 2, 3, 4, 5, 6))
      val result = stream.par(3)(x => Task(x * 2)).toList.sync()
      result.sorted shouldEqual List(2, 4, 6, 8, 10, 12) // Sorting to account for parallel execution order
    }
    "use parFast to quickly process with many threads" in {
      val stream = Stream.emits(0L until 1_000_000L)
      val add = new AtomicLong(0L)
      stream.parFast() { i =>
        add.addAndGet(i)
        Task.unit
      }.sync()
      add.get() should be(499999500000L)
    }
    "append two streams" in {
      val stream1 = Stream.emits(List(1, 2, 3))
      val stream2 = Stream.emits(List(4, 5, 6))
      val result = stream1.append(stream2).toList.sync()
      result shouldEqual List(1, 2, 3, 4, 5, 6)
    }
    "handle empty streams correctly" in {
      val emptyStream = Stream.empty[Int]
      val result = emptyStream.toList.sync()
      result shouldEqual List.empty[Int]
    }
    "convert a stream to a list" in {
      val stream = Stream.emits(List(1, 2, 3, 4, 5))
      val result = stream.toList.sync()
      result shouldEqual List(1, 2, 3, 4, 5)
    }
    "filter out None from list" in {
      val stream = Stream(Some(1), None, Some(2), None, Some(3))
      val result = stream.unNone.toList.sync()
      result should be(List(1, 2, 3))
    }
    "write a String to a File via byte stream" in {
      val stream = Stream.emits("Hello, World!".getBytes("UTF-8").toList)
      val file = new File("test.txt")
      val bytes = stream.toFile(file).sync()
      bytes should be(13)
      Files.readString(file.toPath) should be("Hello, World!")
      file.delete()
    }
    "read bytes into lines" in {
      val stream = Stream.emits(
        """This
          |is
          |multiple
          |lines""".stripMargin.getBytes("UTF-8").toIndexedSeq)
      stream.lines.toList.sync() should be(List("This", "is", "multiple", "lines"))
    }
    "verify stream merging works and lazily builds" in {
      val s1 = Stream(1, 2, 3)
      val s2 = Stream.force(Task.sleep(1.second).map(_ => Stream(4, 5, 6)))
      var set = Set.empty[Int]
      val merged = Stream.merge(Task(Pull.fromList(List(s1, s2)))).foreach { i =>
        set += i
      }
      set should be(Set.empty)
      val start = System.currentTimeMillis()
      val fiber = merged.drain.start()
      Task.sleep(10.milliseconds).flatMap { _ =>
        set should be(Set(1, 2, 3))
        fiber.map { _ =>
          set should be(Set(1, 2, 3, 4, 5, 6))
          val elapsed = System.currentTimeMillis() - start
          elapsed should be > 1000L
        }
      }.sync()
    }
  }
}