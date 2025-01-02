package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}

import scala.concurrent.duration.DurationInt

class BlockableSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  "Blockable" should {
    "handle a completable partway through a chain" in {
      Task.sleep(250.millis).completable[String].flatMap { c =>
        Task.sleep(100.millis).foreach(_ => c.success("Finished!")).start()
        c
      }.map(_ should be("Finished!"))
    }
  }
}
