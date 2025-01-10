package spec

import cats.effect.unsafe.implicits.global
import org.scalatest.concurrent.TimeLimitedTests
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Minute, Span}
import org.scalatest.wordspec.AnyWordSpec
import rapid.Task
import rapid.cats._

class ExtrasSpec extends AnyWordSpec with Matchers with TimeLimitedTests {
  override def timeLimit: Span = Span(1, Minute)

  "TaskExtras" should {
    "convert Task to IO" in {
      val task = Task(42)
      val io = task.toIO
      io.unsafeRunSync() should be(42)
    }
  }
//  "StreamExtras" should {
//    "convert Stream to fs2.Stream" in {
//      val stream = rapid.Stream.fromList(List(1, 2, 3))
//      val fs2Stream = stream.toFS2
//      val result = fs2Stream.compile.toList.unsafeRunSync()
//      result should be(List(1, 2, 3))
//    }
//  }
}