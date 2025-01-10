package spec

import org.scalatest.concurrent.TimeLimitedTests
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Minute, Span}
import org.scalatest.time.SpanSugar.convertIntToGrainOfTime
import org.scalatest.wordspec.AnyWordSpec
import rapid._

import java.util.concurrent.atomic.AtomicInteger

class BasicsSpec extends AnyWordSpec with Matchers with TimeLimitedTests {
  override def timeLimit: Span = Span(1, Minute)

  "Basics" should {
    "handle a simple task" in {
      val i = Task {
        5 * 5
      }
      i.await() should be(25)
    }
    "handle a simple task mapping" in {
      val i = Task {
        5 * 5
      }
      val s = i.map { v =>
        s"Value: $v"
      }
      s.await() should be("Value: 25")
    }
    "handle flat mapping" in {
      val task = (1 to 10).foldLeft(Task(0))((t, i) => t.flatMap { total =>
        Task(total + i)
      })
      val result = task.await()
      result should be(55)
    }
    "chain fibers together" in {
      val start = System.currentTimeMillis()
      Task.sleep(250.millis).start().flatMap { _ =>
        Task.sleep(250.millis).start().flatMap { _ =>
          Task.sleep(250.millis).start()
        }
      }.sync()
      val elapsed = System.currentTimeMillis() - start
      elapsed should be >= 750L
    }
    "throw an error and recover" in {
      val result = Task[String](throw new RuntimeException("Die Die Die"))
        .handleError { _ =>
          Task.pure("Recovered")
        }
        .sync()
      result should be("Recovered")
    }
    "raise an error and recover" in {
      val result = Task.error(new RuntimeException("Die Die Die"))
        .handleError { _ =>
          Task.pure("Recovered")
        }
        .sync()
      result should be("Recovered")
    }
    "process a list of tasks to a task with a list" in {
      val list = List(
        Task("One"), Task("Two"), Task("Three")
      )
      list.tasks.sync() should be(List("One", "Two", "Three"))
    }
    "verify a singleton task works properly sequentially" in {
      val counter = new AtomicInteger(0)
      val task = Task {
        counter.incrementAndGet()
      }.singleton
      val verify = for {
        one <- task
        two <- task
        three <- task
      } yield {
        one should be(1)
        two should be(1)
        three should be(1)
      }
      verify.sync()
    }
    "verify a singleton task works properly concurrently" in {
      val counter = new AtomicInteger(0)
      val task = Task.sleep(1.second).map { _ =>
        counter.incrementAndGet()
      }.singleton
      val verify = for {
        _ <- Task.unit
        f1 = task.start()
        f2 = task.start()
        f3 = task.start()
        _ = counter.get() should be(0)
        one <- f1
        two <- f2
        three <- f3
      } yield {
        one should be(1)
        two should be(1)
        three should be(1)
      }
      verify.sync()
    }
    "verify the same task is functionally evaluated" in {
      val task = Task(System.currentTimeMillis()).map(_ / 1000.0)
      val now = System.currentTimeMillis() / 1000.0
      task.sync() shouldBe >=(now)
      Task.sleep(1.second).sync()
      val later = System.currentTimeMillis() / 1000.0
      task.sync() shouldBe >(now)
      task.sync() shouldBe >=(later)
    }
  }
}
