//package spec
//
//import org.scalatest.concurrent.TimeLimitedTests
//import org.scalatest.matchers.should.Matchers
//import org.scalatest.time.{Minute, Span}
//import org.scalatest.wordspec.AnyWordSpec
//import rapid._
//
//import scala.concurrent.duration._
//
//class FiberWaitingSpec extends AnyWordSpec with Matchers with TimeLimitedTests {
//  override def timeLimit: Span = Span(1, Minute)
//
//  "Fiber waiting" should {
//    "await with a timeout" in {
//      println("f1")
//      val task = Task {
//        Thread.sleep(1000); 42
//      }
//      val fiber = task.start()
//      fiber.await(500.millis) shouldEqual None
//      fiber.await(1500.millis) shouldEqual Some(42)
//    }
//    "chain fibers together" in {
//      println("f2")
//      val start = System.currentTimeMillis()
//      Task.sleep(250.millis).start().flatMap { _ =>
//        Task.sleep(250.millis).start().flatMap { _ =>
//          Task.sleep(250.millis).start()
//        }
//      }.sync()
//      val elapsed = System.currentTimeMillis() - start
//      println("f3")
//      elapsed should be >= 750L
//    }
//  }
//}
