package spec

import org.scalatest.BeforeAndAfterAll
import org.scalatest.wordspec.AsyncWordSpec

trait TestBootstrap extends AsyncWordSpec with BeforeAndAfterAll {
  override protected def beforeAll(): Unit = {
    System.setProperty("rapid.tests.usePlatformTimer", "true")
    super.beforeAll()
  }
}