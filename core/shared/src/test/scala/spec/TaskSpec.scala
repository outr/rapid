package spec

import org.scalatest.concurrent.TimeLimitedTests
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Minute, Span}
import org.scalatest.wordspec.AnyWordSpec
import rapid._

class TaskSpec extends AnyWordSpec with Matchers with TimeLimitedTests {
  override def timeLimit: Span = Span(1, Minute)

  "Tasks" should {
    "execute a simple task" in {
      val task = Task { 5 * 5 }
      task.sync() shouldEqual 25
    }
    "map a task result" in {
      val task = Task { 5 * 5 }
      val mappedTask = task.map { result => s"Result: $result" }
      mappedTask.sync() shouldEqual "Result: 25"
    }
    "flatMap tasks" in {
      val task = Task { 5 * 5 }
      val flatMappedTask = task.flatMap { result => Task { result + 10 } }
      flatMappedTask.sync() shouldEqual 35
    }
    "handle task failures" in {
      val task = Task { throw new RuntimeException("Failure") }
      an[RuntimeException] should be thrownBy task.sync()
    }
    "parallel process a list of zero tasks" in {
      val list: List[Task[String]] = Nil
      list.tasksPar.map(list => list should be(Nil)).sync()
    }
    "handle repeat functionality" in {
      var counter = 0
      Task(counter += 1).repeat(Repeat.Times(5)).sync()
      counter should be(5)
    }
    "propagate errors thrown by the guarantee block on a successful task" in {
      val task = Task { 42 }.guarantee(Task {
        throw new RuntimeException("cleanup boom")
      })
      val ex = the[RuntimeException] thrownBy task.sync()
      ex.getMessage should be("cleanup boom")
    }
    "preserve the primary error and attach the cleanup error as suppressed" in {
      val task = Task[Int] { throw new RuntimeException("primary") }.guarantee(Task {
        throw new RuntimeException("cleanup boom")
      })
      val ex = the[RuntimeException] thrownBy task.sync()
      ex.getMessage should be("primary")
      ex.getSuppressed.map(_.getMessage).toList should contain("cleanup boom")
    }
    "run the guarantee block on success and return the primary value" in {
      var ran = false
      val task = Task { 42 }.guarantee(Task { ran = true })
      task.sync() shouldEqual 42
      ran should be(true)
    }
    "run the guarantee block on failure and propagate the primary error" in {
      var ran = false
      val task = Task[Int] { throw new RuntimeException("primary") }.guarantee(Task { ran = true })
      val ex = the[RuntimeException] thrownBy task.sync()
      ex.getMessage should be("primary")
      ran should be(true)
    }
  }
}