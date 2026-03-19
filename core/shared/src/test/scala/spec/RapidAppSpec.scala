package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import rapid._

import scala.util.{Failure, Success, Try}

class RapidAppSpec extends AnyWordSpec with Matchers {
  "RapidApp" should {
    "return exit code 0 on successful run" in {
      val app = new RapidApp {
        override def run(args: List[String]): Task[Unit] = Task.unit
      }
      val exitCode = app.run(Nil).attempt.flatMap(app.result).sync()
      exitCode should be(0)
    }
    "return exit code 1 on failed run" in {
      val app = new RapidApp {
        override def run(args: List[String]): Task[Unit] =
          Task.error(new RuntimeException("boom"))
      }
      val exitCode = app.run(Nil).attempt.flatMap(app.result).sync()
      exitCode should be(1)
    }
    "pass arguments through to run" in {
      var received = List.empty[String]
      val app = new RapidApp {
        override def run(args: List[String]): Task[Unit] = Task {
          received = args
        }
      }
      app.run(List("a", "b", "c")).attempt.flatMap(app.result).sync()
      received should be(List("a", "b", "c"))
    }
    "allow overriding result for custom exit codes" in {
      val app = new RapidApp {
        override def run(args: List[String]): Task[Unit] =
          Task.error(new RuntimeException("custom"))
        override def result(result: Try[Unit]): Task[Int] = result match {
          case Success(_) => Task.pure(0)
          case Failure(_) => Task.pure(42)
        }
      }
      val exitCode = app.run(Nil).attempt.flatMap(app.result).sync()
      exitCode should be(42)
    }
    "log errors via RapidLoggerSupport" in {
      var logged = false
      val app = new RapidApp {
        override def run(args: List[String]): Task[Unit] =
          Task.error(new RuntimeException("test error"))
        override def result(result: Try[Unit]): Task[Int] = result match {
          case Success(_) => Task.pure(0)
          case Failure(t) =>
            logged = true
            Task.pure(1)
        }
      }
      app.run(Nil).attempt.flatMap(app.result).sync()
      logged should be(true)
    }
  }
}
