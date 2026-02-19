package spec

import org.scalatest.concurrent.TimeLimitedTests
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Minute, Span}
import org.scalatest.wordspec.AnyWordSpec
import rapid._

class TraceSpec extends AnyWordSpec with Matchers with TimeLimitedTests {
  override def timeLimit: Span = Span(1, Minute)

  private val FileName = "TraceSpec.scala"

  private def taskFrames(t: Throwable): Array[StackTraceElement] =
    t.getStackTrace.filter(_.getMethodName.startsWith("<"))

  private def allFrames(t: Throwable): Array[StackTraceElement] =
    t.getStackTrace

  // Helper methods defined at known lines for trace assertions.
  // Each captures a sourcecode.Line to make assertions resilient to file edits.
  private def simpleFailingTask: (Task[Int], Int) = {
    val line = implicitly[sourcecode.Line].value; val t = Task(1 / 0)
    (t, line)
  }

  "Trace" should {
    "produce exactly one <apply> frame at position 1 for a simple failing Task" in {
      val (task, applyLine) = simpleFailingTask
      val ex = intercept[ArithmeticException] { task.sync() }

      val all = allFrames(ex)
      val tasks = taskFrames(ex)

      tasks.length should be(1)
      tasks(0).getMethodName should be("<apply>")
      tasks(0).getFileName should be(FileName)
      tasks(0).getLineNumber should be(applyLine)

      all(1) should be(tasks(0))
    }

    "produce <apply> then <flatMap> at positions 1-2 for a flatMap that fails" in {
      val applyLine = implicitly[sourcecode.Line].value; def failing(x: Int): Task[Int] = Task(x / 0)
      val flatMapLine = implicitly[sourcecode.Line].value; val pipeline = Task(42).flatMap(failing)

      val ex = intercept[ArithmeticException] { pipeline.sync() }
      val all = allFrames(ex)
      val tasks = taskFrames(ex)

      tasks.length should be(2)

      tasks(0).getMethodName should be("<apply>")
      tasks(0).getFileName should be(FileName)
      tasks(0).getLineNumber should be(applyLine)

      tasks(1).getMethodName should be("<flatMap>")
      tasks(1).getFileName should be(FileName)
      tasks(1).getLineNumber should be(flatMapLine)

      all(1) should be(tasks(0))
      all(2) should be(tasks(1))

      tasks(0).getLineNumber should be < tasks(1).getLineNumber
    }

    "produce exactly one <map> frame when map's function throws" in {
      val mapLine = implicitly[sourcecode.Line].value; val pipeline = Task(42).map(_ / 0)

      val ex = intercept[ArithmeticException] { pipeline.sync() }
      val all = allFrames(ex)
      val tasks = taskFrames(ex)

      tasks.length should be(1)
      tasks(0).getMethodName should be("<map>")
      tasks(0).getFileName should be(FileName)
      tasks(0).getLineNumber should be(mapLine)
      all(1) should be(tasks(0))
    }

    "produce <apply> then <flatMap> with correct enclosing names from separate functions" in {
      def divide(a: Int, b: Int) = Task(a / b)
      val divideLine = implicitly[sourcecode.Line].value - 1

      def process(x: Int) = divide(x, 0)

      val flatMapLine = implicitly[sourcecode.Line].value; val pipeline = Task(42).flatMap(process)

      val ex = intercept[ArithmeticException] { pipeline.sync() }
      val tasks = taskFrames(ex)

      tasks.length should be(2)

      tasks(0).getMethodName should be("<apply>")
      tasks(0).getLineNumber should be(divideLine)
      tasks(0).getClassName should include("divide")

      tasks(1).getMethodName should be("<flatMap>")
      tasks(1).getLineNumber should be(flatMapLine)
    }

    "exclude completed branches -- only the failing flatMap appears" in {
      def step1 = Task(42)
      def step2(x: Int) = Task(x + 1)
      val failLine = implicitly[sourcecode.Line].value; def failing(x: Int): Task[Int] = Task(x / 0)

      var sideEffect = 0
      val fm1Line = implicitly[sourcecode.Line].value; val p1 = step1.flatMap(step2)
      val fm2Line = implicitly[sourcecode.Line].value; val pipeline = p1.flatMap(failing)

      val ex = intercept[ArithmeticException] { pipeline.sync() }
      sideEffect = 1

      val tasks = taskFrames(ex)

      tasks.length should be(2)

      tasks(0).getMethodName should be("<apply>")
      tasks(0).getLineNumber should be(failLine)

      tasks(1).getMethodName should be("<flatMap>")
      tasks(1).getLineNumber should be(fm2Line)

      tasks.exists(_.getLineNumber == fm1Line) should be(false)
    }

    "handle a three-level flatMap chain with only the failing branch in trace" in {
      val aLine = implicitly[sourcecode.Line].value; def a() = Task(1)
      val bLine = implicitly[sourcecode.Line].value; def b(x: Int) = Task(x + 1)
      val cLine = implicitly[sourcecode.Line].value; def c(x: Int) = Task(x / 0)
      def d(x: Int) = Task(x.toString)

      val fm1Line = implicitly[sourcecode.Line].value; val s1 = a().flatMap(b)
      val fm2Line = implicitly[sourcecode.Line].value; val s2 = s1.flatMap(c)
      val fm3Line = implicitly[sourcecode.Line].value; val pipeline = s2.flatMap(d)

      val ex = intercept[ArithmeticException] { pipeline.sync() }
      val tasks = taskFrames(ex)

      tasks(0).getMethodName should be("<apply>")
      tasks(0).getLineNumber should be(cLine)

      val flatMaps = tasks.filter(_.getMethodName == "<flatMap>")
      flatMaps.length should be(2)
      flatMaps(0).getLineNumber should be(fm2Line)
      flatMaps(1).getLineNumber should be(fm3Line)

      tasks.exists(_.getLineNumber == aLine) should be(false)
      tasks.exists(_.getLineNumber == bLine) should be(false)
      tasks.exists(_.getLineNumber == fm1Line) should be(false)
    }

    "produce <map> then <flatMap> for map failure inside a flatMap" in {
      val mapLine = implicitly[sourcecode.Line].value; def failMap(x: Int) = Task(x).map(_ / 0)
      val fmLine = implicitly[sourcecode.Line].value; val pipeline = Task(42).flatMap(failMap)

      val ex = intercept[ArithmeticException] { pipeline.sync() }
      val all = allFrames(ex)
      val tasks = taskFrames(ex)

      tasks.length should be(2)
      tasks(0).getMethodName should be("<map>")
      tasks(0).getLineNumber should be(mapLine)
      tasks(1).getMethodName should be("<flatMap>")
      tasks(1).getLineNumber should be(fmLine)

      all(1) should be(tasks(0))
      all(2) should be(tasks(1))
    }

    "handleError re-throw preserves accurate trace for the re-thrown error" in {
      val pipeline = Task(1 / 0)
        .handleError(t => Task.error[Int](new RuntimeException("wrapped", t)))

      val ex = intercept[RuntimeException] { pipeline.sync() }
      ex.getMessage should be("wrapped")
      ex.getCause shouldBe a[ArithmeticException]

      val tasks = taskFrames(ex)
      tasks.length should be >= 1
      tasks.exists(_.getClassName.contains("error")) should be(true)
      tasks.exists(_.getMethodName == "<flatMap>") should be(true)
    }

    "all task frames use the correct file name" in {
      val pipeline = Task(1).flatMap(x => Task(x).map(_ / 0))
      val ex = intercept[ArithmeticException] { pipeline.sync() }

      val tasks = taskFrames(ex)
      tasks.length should be >= 1
      tasks.foreach { f =>
        f.getFileName should be(FileName)
      }
    }

    "task frames appear immediately after the JVM throw-site frame" in {
      val pipeline = Task(1).flatMap(x => Task(x / 0))
      val ex = intercept[ArithmeticException] { pipeline.sync() }

      val all = allFrames(ex)
      val tasks = taskFrames(ex)

      tasks.length should be >= 1
      for (i <- tasks.indices) {
        all(1 + i) should be(tasks(i))
      }
    }

    "no interpreter frames from SynchronousFiber appear in the stack trace" in {
      val ex = intercept[ArithmeticException] { Task(1 / 0).sync() }

      val interpreterFrames = allFrames(ex).filter { f =>
        f.getClassName.startsWith("rapid.fiber.SynchronousFiber") &&
          !f.getMethodName.startsWith("<")
      }
      interpreterFrames.length should be(0)
    }

    "line numbers increase from innermost to outermost frame" in {
      val innerLine = implicitly[sourcecode.Line].value; def inner(x: Int): Task[Int] = Task(x / 0)
      val outerLine = implicitly[sourcecode.Line].value; val pipeline = Task(42).flatMap(inner)

      val ex = intercept[ArithmeticException] { pipeline.sync() }
      val tasks = taskFrames(ex)

      tasks.length should be(2)
      tasks(0).getLineNumber should be(innerLine)
      tasks(1).getLineNumber should be(outerLine)
      tasks(0).getLineNumber should be < tasks(1).getLineNumber
    }

    "deeply nested for-comprehension produces correct trace chain" in {
      def step1 = Task(10)
      def step2(x: Int) = Task(x * 2)
      def step3(x: Int): Task[Int] = Task(x / 0)

      val forLine = implicitly[sourcecode.Line].value
      val pipeline = for {
        a <- step1
        b <- step2(a)
        c <- step3(b)
      } yield c

      val ex = intercept[ArithmeticException] { pipeline.sync() }
      val tasks = taskFrames(ex)

      tasks(0).getMethodName should be("<apply>")

      val flatMaps = tasks.filter(_.getMethodName == "<flatMap>")
      flatMaps.length should be >= 1
      flatMaps.foreach(_.getFileName should be(FileName))
    }
  }
}
