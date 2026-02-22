package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid._

import scala.concurrent.duration.DurationInt

class SingleThreadAgentSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  "SingleThreadAgent" should {
    "run work on the agent thread" in {
      val sta = SingleThreadAgent("test")(Task.pure(""))
      sta { _ =>
        Thread.currentThread().getName
      }.map { threadName =>
        threadName should be("test-sta")
      }.guarantee(sta.dispose())
    }
  }
}
