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
      // StatsTaskMonitor no longer wired through Task.monitor in this refactor
      // Task.monitor = monitor
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
    "handle condition" in {
      var counter = 0
      Task.condition(Task {
        counter += 1
        if (counter >= 5) {
          true
        } else {
          false
        }
      }, delay = 25.millis).function {
        counter should be(5)
      }
    }
    "verify start and forget functionality with effect" in {
      var condition = false
      Task.sleep(100.millis).effect(Task {
        condition = true
      }).start()
      Task.condition(Task.function(condition), delay = 25.millis).function {
        condition should be(true)
      }
    }
    "verify timed works properly" in {
      val timer = Timer()
      val timed = Task.timed(timer) {
        Task.sleep(25.millis)
      }
      (0 until 10).map(_ => timed).tasksPar.function {
        timer.elapsedMillis should be >= 250L
        timer.elapsedMillis should be <= 500L
      }
    }
    "verify CompletableTask.onSuccess" in {
      var text = ""
      val completable = Task.completable[String]
      completable.onComplete {
        case scala.util.Success(s) =>
        text = s
        case scala.util.Failure(_) => ()
      }
      completable.success("Test")
      text should be("Test")
    }
    "run Task.and in parallel and return results" in {
      val left = Task.sleep(10.millis).map(_ => 1)
      val right = Task.sleep(10.millis).map(_ => 2)
      val both = left.and(right)
      both.map { case (l, r) =>
        l should be(1)
        r should be(2)
      }
    }
    "preserve original failure when guarantee finalizer fails" in {
      val mainEx = new RuntimeException("main boom")
      val finEx = new RuntimeException("finalizer boom")
      val t = Task.error[String](mainEx).guarantee(Task { throw finEx })
      t.attempt.map {
        case scala.util.Success(_) => fail("expected failure")
        case scala.util.Failure(e) => e should be (mainEx)
      }
    }
    "flatMap from UnitTask into Unit function should not cast Some to Unit" in {
      val t = Task.unit.flatMap { _ => Task.pure(1) }
      t.map { v => v should be (1) }
    }
    "start and wait for four tasks to complete" in {
      val start = System.currentTimeMillis()
      val task = for {
        f1 <- Task.sleep(500.millis).start
        f2 <- Task.sleep(500.millis).start
        f3 <- Task.sleep(500.millis).start
        f4 <- Task.sleep(500.millis).start
        _ <- f1
        _ <- f2
        _ <- f3
        _ <- f4
      } yield ()
      task.function {
        val now = System.currentTimeMillis()
        val elapsed = now - start
        elapsed should be >= 500L
        elapsed should be <= 1_000L
      }
    }
    "verify chunk + fold" in {
      rapid.Stream.emits(0 to 100)
        .chunk(chunkSize = 10)
        .fold(0)((total, values) => Task.pure(total + values.sum))
        .map { total =>
          total should be(5050)
        }
    }
  }
}