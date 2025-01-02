package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.{AnyWordSpec, AsyncWordSpec}
import rapid._

import scala.concurrent.duration.DurationInt

class BasicsAsyncSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  "Basics sync" should {
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
  }
}