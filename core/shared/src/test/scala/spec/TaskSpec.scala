package spec

import org.scalatest.concurrent.TimeLimitedTests
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Minute, Span}
import org.scalatest.wordspec.AnyWordSpec
import rapid._

import scala.concurrent.duration._

class TaskSpec extends AnyWordSpec with Matchers with TimeLimitedTests {
  override def timeLimit: Span = Span(1, Minute)

  "Task" should {
    "execute a simple task" in {
      val task = Task { 5 * 5 }
      task.await() shouldEqual 25
    }
    "map a task result" in {
      val task = Task { 5 * 5 }
      val mappedTask = task.map { result => s"Result: $result" }
      mappedTask.await() shouldEqual "Result: 25"
    }
    "flatMap tasks" in {
      val task = Task { 5 * 5 }
      val flatMappedTask = task.flatMap { result => Task { result + 10 } }
      flatMappedTask.await() shouldEqual 35
    }
    "handle task failures" in {
      val task = Task { throw new RuntimeException("Failure") }
      an[RuntimeException] should be thrownBy task.await()
    }
    "sleep for a duration" in {
      val start = System.currentTimeMillis()
      Task.sleep(500.millis).await()
      val elapsed = System.currentTimeMillis() - start
      elapsed should be >= 500L
    }
    "utilize completable" in {
      val start = System.currentTimeMillis()
      val c = Task.completable[String]
      Task.sleep(500.millis).map { _ =>
        c.success("Success!")
      }.start()
      val result = c.await()
      result should be("Success!")
      val elapsed = System.currentTimeMillis() - start
      elapsed should be >= 500L
    }
    "utilize for-comprehension" in {
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
    "process a longer list of tasks with delays in parallel" in {
      (0 until 100_000).map(i => Task.sleep(500.millis).map(_ => i * 2)).tasksPar.map { list =>
        list.sum should be(1409965408)
      }.sync()
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
  }
}