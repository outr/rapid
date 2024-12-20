package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.time.SpanSugar.convertIntToGrainOfTime
import org.scalatest.wordspec.AnyWordSpec
import rapid._

import scala.concurrent.CancellationException

class BasicsSyncSpec extends AnyWordSpec with Matchers {
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
    // TODO: Re-enable once this can work with JS
    /*"cancel a running task" in {
      if (Platform.supportsCancel) {
        val start = System.currentTimeMillis()
        val fiber = Task.sleep(1.hour).map(_ => "Never").start()
        fiber.cancel().sync()
        a[CancellationException] should be thrownBy fiber.sync()
        (System.currentTimeMillis() - start) should be < 1000L
      }
    }*/
  }
}