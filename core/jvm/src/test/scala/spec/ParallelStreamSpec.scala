package spec

import org.scalatest.concurrent.TimeLimitedTests
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Minute, Span}
import org.scalatest.time.SpanSugar.convertIntToGrainOfTime
import org.scalatest.wordspec.AnyWordSpec
import rapid._

import java.util.concurrent.atomic.AtomicInteger

class ParallelStreamSpec extends AnyWordSpec with Matchers with TimeLimitedTests {
  override def timeLimit: Span = Span(1, Minute)

  "ParallelStream" should {
    "correctly drain a simple stream" in {
      val counter = new AtomicInteger(0)
      val stream = Stream.emits(List(1, 2, 3, 4, 5)).par() { i =>
        Task(counter.addAndGet(i))
      }
      val task = stream.drain
      task.sync()
      counter.get() should be(15)
    }
    "correctly count a simple stream" in {
      val counter = new AtomicInteger(0)
      val stream = Stream.emits(List(1, 2, 3, 4, 5)).par() { i =>
        Task(counter.addAndGet(i))
      }
      val task = stream.count
      task.sync() should be(5)
      counter.get() should be(15)
    }
    "correctly toList a simple stream" in {
      val stream = Stream.emits(List(1, 2, 3, 4, 5)).par() { i =>
        Task(i * 2)
      }
      val task = stream.toList
      task.sync() should be(List(2, 4, 6, 8, 10))
    }
    "correctly toList with random sleeps" in {
      val stream = Stream.emits(List(1, 2, 3, 4, 5)).par() { i =>
        Task.sleep((Math.random() * 1000).toInt.millis).map(_ => i * 2)
      }
      val task = stream.toList
      task.sync() should be(List(2, 4, 6, 8, 10))
    }
    "correctly toList with random sleeps and overflowing maxBuffer" in {
      val stream = Stream.emits(0 until 100_000).par(maxBuffer = 100) { i =>
        Task(i * 2)
      }
      val task = stream.toList
      task.sync().sum should be(1_409_965_408)
    }
    "correctly collect on a parallel stream" in {
      val stream = Stream.emits(0 until 100_000)
        .par(maxBuffer = 100)(i => Task(i * 2))
        .collect {
          case i if i <= 100 && i >= 90 => i
        }
      stream.toList.map { list =>
        list should be(List(90, 92, 94, 96, 98, 100))
      }.sync()
    }
    "correctly fold on a parallel stream" in {
      val task = Stream.emits(0 until 100_000)
        .par(maxBuffer = 100)(i => Task(i * 2))
        .fold(0)((total, i) => Task(total + i))
      task.sync() should be(1_409_965_408)
    }
    "close source Pull after processing" in {
      @volatile var closed = false
      val base = Pull.fromList(List(1, 2, 3))
      val pull = Pull(base.pull, Task { closed = true })
      val ps = Stream(Task.pure(pull)).par() { i => Task.pure(i) }
      ps.count.sync() shouldEqual 3
      closed shouldBe true
    }
  }
}