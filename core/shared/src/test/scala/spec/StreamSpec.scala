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
          elapsed should be >= 1000L
        }
      }.sync()
    }
    "zip with index correctly and handle empty" in {
      Stream.emits(List("a", "b", "c"))
        .zipWithIndex
        .toList
        .sync() shouldEqual List(("a", 0), ("b", 1), ("c", 2))

      Stream.empty[Int]
        .zipWithIndex
        .toList
        .sync() shouldEqual Nil
    }
    "distinct keeps first occurrence and preserves order" in {
      val stream = Stream.emits(List(1, 2, 1, 3, 2, 4, 4, 5))
      val result = stream.distinct.toList.sync()
      result shouldEqual List(1, 2, 3, 4, 5)
    }
    "groupSequential groups adjacent items by key" in {
      // Keys: a a a | b b | a | c c
      val in = List("aa", "a", "aaa", "bb", "b", "a", "cc", "c")
      val stream = Stream.emits(in).groupSequential((s: String) => s.head)
      val groups = stream.toList.sync()
      groups.map(g => g.group -> g.results) should be(List(
        ('a', List("aa", "a", "aaa")),
        ('b', List("bb", "b")),
        ('a', List("a")),
        ('c', List("cc", "c"))
      ))
    }
    "group by separator" in {
      val s = Stream.emits(List(1, 2, 0, 3, 4, 0, 5, 6, 7, 0))
      val groups = s.group(_ == 0).toList.sync()
      groups shouldEqual List(
        List(1, 2),
        List(3, 4),
        List(5, 6, 7)
      )
    }
    "takeWhileWithFirst stops when predicate fails vs first element" in {
      val in = List(10, 11, 12, 19, 20, 21)
      val out = Stream.emits(in).takeWhileWithFirst((first, cur) => (cur - first) < 10).toList.sync()
      out shouldEqual List(10, 11, 12, 19) // 19 - 10 = 9 ok; 20 would be 10 -> stop before 20
    }
    "slice selects a half-open interval and stops" in {
      val out = Stream.emits(0 until 10).slice(3, 7).toList.sync()
      out shouldEqual List(3, 4, 5, 6)
    }
    "sliding with step works at tail boundaries" in {
      val out = Stream.emits(1 to 7).sliding(size = 3, step = 2).toList.sync()
      out shouldEqual List(
        Vector(1, 2, 3),
        Vector(3, 4, 5),
        Vector(5, 6, 7),
        Vector(7)               // tail remainder
      )
    }
    "evalTap runs effects without changing values" in {
      val seen = scala.collection.mutable.ArrayBuffer.empty[Int]
      val out = Stream.emits(List(1, 2, 3))
        .evalTap(i => Task { seen += i })
        .toList
        .sync()
      out shouldEqual List(1, 2, 3)
      seen.toList shouldEqual List(1, 2, 3)
    }
    "zip / zipWith / zipAll behave as expected" in {
      val a = Stream.emits(List(1, 2, 3))
      val b = Stream.emits(List(10, 20))
      a.zip(b).toList.sync() shouldEqual List((1, 10), (2, 20))
      a.zipWith(b)(_ + _).toList.sync() shouldEqual List(11, 22)
      a.zipAll(b, thisElem = -1, otherElem = -2).toList.sync() shouldEqual List(
        (1, 10), (2, 20), (3, -2)
      )
    }
    "exists / forall / find / contains work" in {
      val s = Stream.emits(List(2, 4, 6, 8))
      s.exists(_ == 6).sync() shouldBe true
      s.exists(_ == 5).sync() shouldBe false
      s.forall(_ % 2 == 0).sync() shouldBe true
      s.find(_ > 5).sync() shouldEqual Some(6)
      s.contains(8).sync() shouldBe true
      s.contains(3).sync() shouldBe false
    }
    "reduce folds non-empty and errors on empty" in {
      val s = Stream.emits(List(1, 2, 3, 4))
      s.reduce((a: Int, b: Int) => Task(a + b)).sync() shouldEqual 10

      val empty = Stream.empty[Int]
      val ex = intercept[NoSuchElementException] {
        empty.reduce((a: Int, b: Int) => Task(a + b)).sync()
      }
      ex.getMessage should include ("Stream.reduce on empty stream")
    }
    "first / firstOption / last / lastOption" in {
      val s = Stream.emits(List(7, 8, 9))
      s.first.sync() shouldEqual 7
      s.firstOption.sync() shouldEqual Some(7)
      s.last.sync() shouldEqual 9
      s.lastOption.sync() shouldEqual Some(9)

      val e = Stream.empty[Int]
      e.firstOption.sync() shouldEqual None
      e.lastOption.sync() shouldEqual None
      intercept[NoSuchElementException](e.first.sync())
      intercept[NoSuchElementException](e.last.sync())
    }
    "zipWithIndexAndTotal computes total first then pairs" in {
      val s = Stream.emits(List("a", "b", "c")).zipWithIndexAndTotal
      s.toList.sync() shouldEqual List(
        ("a", 0, 3),
        ("b", 1, 3),
        ("c", 2, 3)
      )
    }
    "materializedCursorEvalMap basic scenario" in {
      // Keep only values not equal to the immediately previous output (simple de-dup)
      val s = Stream.emits(List(1, 1, 2, 2, 2, 3))
      val out =
        s.materializedCursorEvalMap[Int, Int](
          (next, cursor) => {
            val keep = cursor.previous(1).forall(_ != next)
            if (keep) Task.pure(cursor.add(next)) else Task.pure(cursor)
          }
        ).toList.sync()

      out shouldEqual List(1, 2, 3)
    }
    "parFast stops on first failure and propagates error" in {
      val s = Stream.emits(1 to 10000)
      @volatile var processed = 0
      val err = intercept[RuntimeException] {
        s.parFast(threads = 4) { i =>
          if (i == 5000) Task.error(new RuntimeException("boom"))
          else Task { processed += 1 }
        }.sync()
      }
      err.getMessage shouldBe "boom"
      processed should be < 10000
    }
    "append ++ alias produces same result" in {
      val s1 = Stream.emits(List(1, 2))
      val s2 = Stream.emits(List(3))
      s1.append(s2).toList.sync() shouldEqual List(1, 2, 3)
      (s1 ++ s2).toList.sync() shouldEqual List(1, 2, 3)
    }
    "evalForge works" in {
      val forge = Forge[Int, Int](i => Task(i * 3))
      Stream.emits(List(2, 3, 4)).evalForge(forge).toList.sync() shouldEqual List(6, 9, 12)
    }
    "take n elements then stop" in {
      val out = Stream.emits(1 to 10).take(3).toList.sync()
      out shouldEqual List(1, 2, 3)
    }
    "drop n elements then emit the rest" in {
      val out = Stream.emits(1 to 5).drop(2).toList.sync()
      out shouldEqual List(3, 4, 5)
    }
    "dropWhile drops until predicate fails then emits everything" in {
      val out = Stream.emits(List(1, 2, 3, 1, 2, 3)).dropWhile(_ < 3).toList.sync()
      out shouldEqual List(3, 1, 2, 3)
    }
    "slice selects half-open range [from, until)" in {
      val out = Stream.emits(0 until 10).slice(4, 8).toList.sync()
      out shouldEqual List(4, 5, 6, 7)
    }
    "sliding windows with default step=1" in {
      val out = Stream.emits(1 to 5).sliding(size = 3).toList.sync()
      out shouldEqual List(
        Vector(1, 2, 3),
        Vector(2, 3, 4),
        Vector(3, 4, 5),
        Vector(4, 5),
        Vector(5)
      )
    }
    "scanLeft accumulates and emits intermediate states" in {
      val out = Stream.emits(List(1, 2, 3, 4))
        .scanLeft(0)(_ + _)
        .toList
        .sync()
      out shouldEqual List(1, 3, 6, 10)
    }
    "fold accumulates via effect and returns final value" in {
      val out = Stream.emits(List(1, 2, 3, 4))
        .fold(0)((acc, n) => Task(acc + n))
        .sync()
      out shouldEqual 10
    }
    "evalFlatMap flattens Option results" in {
      val out = Stream.emits(1 to 6)
        .evalFlatMap(n => Task(if (n % 2 == 0) Some(n * 10) else None))
        .toList
        .sync()
      out shouldEqual List(20, 40, 60)
    }
    "evalForeach (alias evalTap) runs effects without altering stream" in {
      val seen = scala.collection.mutable.ArrayBuffer.empty[Int]
      val out = Stream.emits(List(3, 4))
        .evalForeach(n => Task { seen += n })
        .toList
        .sync()
      out shouldEqual List(3, 4)
      seen.toList shouldEqual List(3, 4)
    }
    "intersperse inserts separators between elements" in {
      val out = Stream.emits(List("a", "b", "c"))
        .intersperse("|")
        .toList
        .sync()
      out shouldEqual List("a", "|", "b", "|", "c")
    }
    "partition splits by predicate into left/right streams" in {
      val (evens, odds) = Stream.emits(1 to 6).partition(_ % 2 == 0)
      evens.toList.sync() shouldEqual List(2, 4, 6)
      odds.toList.sync()  shouldEqual List(1, 3, 5)
    }
    "groupBy builds a Map[K, List[V]] and preserves per-key order" in {
      val out = Stream.emits(List("aa", "a", "bbb", "b", "cc"))
        .groupBy(_.head)
        .sync()
      out('a') shouldEqual List("aa", "a")
      out('b') shouldEqual List("bbb", "b")
      out('c') shouldEqual List("cc")
    }
    "count returns element count" in {
      Stream.emits(1 to 123).count.sync() shouldEqual 123
      Stream.empty[Int].count.sync() shouldEqual 0
    }
    "toVector collects all elements" in {
      Stream.emits(List(5, 6, 7)).toVector.sync() shouldEqual Vector(5, 6, 7)
    }
    "ParallelStream toList preserves input order and filters None" in {
      val in  = 1 to 20
      val ps  = Stream.emits(in).par(maxThreads = 4, maxBuffer = 64) { i =>
        Task.pure(i * 10) // plain value
      }.collect { case x if (x / 10) % 2 == 0 => x } // keep evens
      ps.toList.sync() shouldEqual in.filter(_ % 2 == 0).map(_ * 10).toList
    }
    "ParallelStream collect applies PartialFunction after forge" in {
      val ps = Stream.emits(1 to 10)
        .par(maxThreads = 3) { i => Task.pure(i) }     // plain value
        .collect { case x if x % 3 == 0 => x * 2 }
      ps.toList.sync() shouldEqual List(6, 12, 18)
    }
    "ParallelStream count counts only Some results" in {
      val ps = Stream.emits(1 to 10)
        .par(maxThreads = 2) { i => Task.pure(i) }
        .collect { case i if i <= 7 => i }
      ps.count.sync() shouldEqual 7
    }
    "ParallelStream fold accumulates effectfully" in {
      val ps = Stream.emits(1 to 5).par(maxThreads = 2) { i => Task.pure(i) }
      val sum = ps.fold(0)((acc, r) => Task.pure(acc + r)).sync()
      sum shouldEqual 15
    }
    "ParallelStream drain runs side effects exactly once per kept element" in {
      val seen = new java.util.concurrent.atomic.AtomicInteger(0)
      val ps = Stream.emits(1 to 50).par(maxThreads = 4) { i =>
        Task.pure(i) // no filtering here
      }
      ps.drain.sync()
      seen.get() shouldEqual 0
      val kept = Stream.emits(1 to 50).par(maxThreads = 4) { i =>
        Task {
          if (i % 5 == 0) seen.incrementAndGet()
          i
        }
      }.collect { case i if i % 5 == 0 => i }
      kept.toList.sync() shouldEqual List(5, 10, 15, 20, 25, 30, 35, 40, 45, 50)
      seen.get() shouldEqual 10
    }
    "ParallelStream respects maxThreads (caps concurrent forge calls)" in {
      val active   = new java.util.concurrent.atomic.AtomicInteger(0)
      val peak     = new java.util.concurrent.atomic.AtomicInteger(0)
      val threads  = 3
      val ps = Stream.emits(1 to 50).par(maxThreads = threads) { _ =>
        Task {
          val now = active.incrementAndGet()
          var prev = peak.get()
          if (now > prev) peak.compareAndSet(prev, now)
          Thread.sleep(5)
          active.decrementAndGet()
          1
        }
      }
      ps.count.sync() shouldEqual 50
      peak.get() should be <= threads
    }
    "ParallelStream handles empty stream" in {
      val ps = Stream.empty[Int].par(maxThreads = 4)(i => Task.pure(i * 2))
      ps.toList.sync() shouldEqual Nil
      ps.count.sync() shouldEqual 0
    }
    "ParallelStream basic stress (large input)" in {
      val n  = 100_000
      val ps = Stream.emits(0 until n).par(maxThreads = 8, maxBuffer = 4096) { i =>
        Task.pure(i)
      }.collect { case i if (i & 1) == 0 => i }
      val out = ps.toList.sync()
      out.size shouldEqual n / 2
      out.headOption shouldEqual Some(0)
      out.lastOption shouldEqual Some(n - (if (n % 2 == 0) 2 else 1))
    }
    "ParallelStream propagates worker failure (first error wins)" in {
      val ps = Stream.emits(1 to 10).par(maxThreads = 4) { i =>
        if (i == 7) Task.error(new RuntimeException("boom"))
        else Task.pure(i)
      }
      val ex = intercept[RuntimeException] {
        ps.toList.sync()
      }
      ex.getMessage shouldBe "boom"
    }
  }
}