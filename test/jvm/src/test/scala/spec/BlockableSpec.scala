package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Platform, Task}

import scala.concurrent.CancellationException
import scala.concurrent.duration.DurationInt

class BlockableSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  "Blockable" should {
    "handle a completable partway through a chain" in {
      Task.sleep(250.millis).completable[String].flatMap { c =>
        Task.sleep(100.millis).foreach(_ => c.success("Finished!")).start()
        c
      }.map(_ should be("Finished!"))
    }
    "cancel a running task" in {
      val start = System.currentTimeMillis()
      val fiber = Task.sleep(1.hour).map(_ => "Never").start()
      fiber.cancel().map { b =>
        b should be(true)
        a[CancellationException] should be thrownBy fiber.sync()
        (System.currentTimeMillis() - start) should be < 1000L
      }
    }
  }
}
