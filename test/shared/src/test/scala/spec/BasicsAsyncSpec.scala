package spec

import org.scalatest.concurrent.AsyncTimeLimitedTests
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Minute, Span}
import org.scalatest.time.SpanSugar.convertIntToGrainOfTime
import org.scalatest.wordspec.AsyncWordSpec
import rapid._
import rapid.monitor.StatsTaskMonitor

import java.util.concurrent.atomic.AtomicInteger

class BasicsAsyncSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers with AsyncTimeLimitedTests {
  override def timeLimit: Span = Span(1, Minute)

  "Basics sync" should {
    val monitor = new StatsTaskMonitor

    "set up stats monitor" in {
      Task.monitor = Opt(monitor)
      Task.succeed
    }
    "handle a simple task" in {
      val i = Task {
        5 * 5
      }
      i.map { n =>
        n should be(25)
      }
    }
    "handle a simple task mapping" in {
      val i = Task {
        5 * 5
      }
      val s = i.map { v =>
        s"Value: $v"
      }
      s.map { s =>
        s should be("Value: 25")
      }
    }
    "handle flat mapping" in {
      val task = (1 to 10).foldLeft(Task(0))((t, i) => t.flatMap { total =>
        Task(total + i)
      })
      task.map { result =>
        result should be(55)
      }
    }
    "throw an error and recover" in {
      val result = Task[String](throw new RuntimeException("Die Die Die"))
        .handleError { _ =>
          Task.pure("Recovered")
        }
      result map { s =>
        s should be("Recovered")
      }
    }
    "process a list of tasks to a task with a list" in {
      val list = List(
        Task("One"), Task("Two"), Task("Three")
      )
      list.tasks.map { list =>
        list should be(List("One", "Two", "Three"))
      }
    }
    "create a Unique value" in {
      Unique.withLength(32).map { s =>
        s.length should be(32)
      }
    }
    "flatMap 10 million times without overflowing" in {
      val max = 10_000_000
      def count(i: Int): Task[Int] = if (i >= max) {
        Task.pure(i)
      } else {
        Task(i + 1).flatMap(count)
      }
      count(0).map { result =>
        result should be(max)
      }
    }
    "create a recursive flatMap method that runs asynchronously" in {
      val max = 10_000_000
      val counter = new AtomicInteger(0)
      def count(i: Int): Task[Int] = if (i >= max) {
        Task.pure(i)
      } else {
        counter.incrementAndGet()
        Task(i + 1).flatMap(count)
      }
      count(0).start()
      def waitForCount(): Task[Unit] = if (counter.get() == max) {
        Task.unit
      } else {
        Task.sleep(100.millis).next(waitForCount())
      }
      waitForCount().map { _ =>
        counter.get() should be(max)
      }
    }
    "write stats out" in {
      println(monitor.report())
      Task.succeed
    }
  }
}