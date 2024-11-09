package spec

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import rapid.Task
import scala.concurrent.duration._

class TaskSpec extends AnyWordSpec with Matchers {
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
  }
}