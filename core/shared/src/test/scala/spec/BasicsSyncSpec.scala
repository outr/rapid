package spec

import org.scalatest.concurrent.TimeLimitedTests
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Minute, Span}
import org.scalatest.time.SpanSugar.convertIntToGrainOfTime
import org.scalatest.wordspec.AnyWordSpec
import rapid._

import scala.concurrent.CancellationException

class BasicsSyncSpec extends AnyWordSpec with Matchers with TimeLimitedTests {
  override def timeLimit: Span = Span(1, Minute)

  "Basics sync" should {
    "handle a simple task" in {
      val i = Task {
        5 * 5
      }
      i.sync() should be(25)
    }
    "handle a simple task mapping" in {
      val i = Task {
        5 * 5
      }
      val s = i.map { v =>
        s"Value: $v"
      }
      s.sync() should be("Value: 25")
    }
    "handle flat mapping" in {
      val task = (1 to 10).foldLeft(Task(0))((t, i) => t.flatMap { total =>
        Task(total + i)
      })
      val result = task.sync()
      result should be(55)
    }
    "throw an error and recover" in {
      val result = Task[String](throw new RuntimeException("Die Die Die"))
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
    "process a Forge with multiple values" in {
      val forge = Forge[Int, String] { i =>
        Task {
          i match {
            case 0 => "Zero"
            case 1 => "One"
            case 2 => "Two"
            case 3 => "Three"
            case 4 => "Four"
            case 5 => "Five"
          }
        }
      }
      forge(1).sync() should be("One")
      forge(5).sync() should be("Five")
    }
    "flatten a task within a task" in {
      val task = Task {
        Task {
          "Success!"
        }
      }
      task.flatten.sync() should be("Success!")
    }
  }
}