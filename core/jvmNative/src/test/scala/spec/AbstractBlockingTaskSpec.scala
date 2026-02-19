package spec

import org.scalatest.concurrent.TimeLimitedTests
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Minute, Span}
import org.scalatest.wordspec.AnyWordSpec
import rapid._

import scala.concurrent.duration._

abstract class AbstractBlockingTaskSpec extends AnyWordSpec with Matchers with TimeLimitedTests {
  override def timeLimit: Span = Span(1, Minute)

  "Tasks (blocking)" should {
    "sleep for a duration" in {
      val start = System.currentTimeMillis()
      Task.sleep(500.millis).sync()
      val elapsed = System.currentTimeMillis() - start
      elapsed should be >= 500L
    }
    "utilize completable" in {
      val start = System.currentTimeMillis()
      val c = Task.completable[String]
      Task.sleep(500.millis).map { _ =>
        c.success("Success!")
      }.start()
      val result = c.sync()
      result should be("Success!")
      val elapsed = System.currentTimeMillis() - start
      elapsed should be >= 500L
    }
    "utilize for-comprehension with sleep" in {
      val result = for {
        one <- Task.sleep(250.millis).map { _ =>
          1
        }
        two <- Task(2)
        three <- Task.pure(3)
      } yield one + two + three
      result.sync() should be(6)
    }
    "process a list of tasks to a task with a list in parallel" in {
      val list = List(
        Task("One"), Task("Two"), Task("Three")
      )
      list.tasksPar.map { list =>
        list should be(List("One", "Two", "Three"))
      }.sync()
    }
  }
}
