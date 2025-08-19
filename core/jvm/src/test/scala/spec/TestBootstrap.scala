package spec

import org.scalatest.BeforeAndAfterAll
import org.scalatest.wordspec.AnyWordSpec

trait TestBootstrap extends AnyWordSpec with BeforeAndAfterAll {
  override protected def beforeAll(): Unit = {
    System.setProperty("rapid.tests.usePlatformTimer", "true")
    super.beforeAll()
  }
}