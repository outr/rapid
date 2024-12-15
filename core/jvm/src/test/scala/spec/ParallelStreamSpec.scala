package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.time.SpanSugar.convertIntToGrainOfTime
import org.scalatest.wordspec.AnyWordSpec
import rapid._

import java.util.concurrent.atomic.AtomicInteger

class ParallelStreamSpec extends AnyWordSpec with Matchers {
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
  }
}