package spec

import org.scalatest.concurrent.TimeLimitedTests
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Minute, Span}
import org.scalatest.wordspec.AnyWordSpec
import rapid._

import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}
import scala.concurrent.duration.DurationInt

abstract class AbstractBlockingStreamSpec extends AnyWordSpec with Matchers with TimeLimitedTests {
  override def timeLimit: Span = Span(1, Minute)

  "Stream (blocking)" should {
    "use parForeach to quickly process with many threads" in {
      val stream = Stream.emits(0L until 1_000_000L)
      val add = new AtomicLong(0L)
      stream.parForeach() { i =>
        add.addAndGet(i)
        Task.unit
      }.sync()
      add.get() should be(499999500000L)
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
      merged.drain.start()
      Task.sleep(10.milliseconds).flatMap { _ =>
        set should be(Set(1, 2, 3))
        Task.sleep(1000.milliseconds).map { _ =>
          set should be(Set(1, 2, 3, 4, 5, 6))
          val elapsed = System.currentTimeMillis() - start
          elapsed should be >= 1000L
        }
      }.sync()
    }
    "parForeach stops on first failure and propagates error" in {
      val s = Stream.emits(1 to 10000)
      @volatile var processed = 0
      val err = intercept[RuntimeException] {
        s.parForeach(threads = 4) { i =>
          if (i == 5000) Task.error(new RuntimeException("boom"))
          else Task { processed += 1 }
        }.sync()
      }
      err.getMessage shouldBe "boom"
      processed should be < 10000
    }
    "verify par doesn't exceed the thread count" in {
      val active = new AtomicInteger(0)
      val maxActive = new AtomicInteger(0)

      def updateMax(v: Int): Unit =
        maxActive.getAndUpdate(m => Math.max(m, v))

      val s = Stream.emits(0 to 100).par(maxThreads = 4) { _ =>
        Task {
          val now = active.incrementAndGet()
          updateMax(now)
        }.flatMap { _ =>
          Task.sleep(100.millis)
        }.guarantee(Task(active.decrementAndGet()))
      }

      s.drain.sync()
      maxActive.get() should be <= 4
    }
    "ParallelStream respects maxThreads (caps concurrent forge calls)" in {
      val active   = new AtomicInteger(0)
      val peak     = new AtomicInteger(0)
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
  }
}
