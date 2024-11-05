package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import rapid._

class BasicsSpec extends AnyWordSpec with Matchers {
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
  }
}
