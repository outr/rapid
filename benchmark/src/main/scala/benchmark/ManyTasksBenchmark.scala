package benchmark

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.openjdk.jmh.annotations._
import rapid.Task
import zio.{Duration, Runtime, Unsafe, ZIO}

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.DurationInt

// jmh:run -i 3 -wi 3 -f1 -t1 -rf JSON -rff benchmarks.json
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
class ManyTasksBenchmark {
  private val tasks = 1_000_000

  private def simpleComputation: Int = math.round(math.sqrt(163.0)).toInt

  private def waitForComplete(completed: AtomicInteger): Unit = {
    while (completed.get() != tasks) {
      Thread.sleep(50)
    }
  }

  @Benchmark
  def ioBenchmark(): Unit = {
    val completed = new AtomicInteger(0)
    (1 to tasks).foreach { _ =>
      IO(simpleComputation).map(_ => completed.incrementAndGet()).unsafeRunAndForget()
    }
    waitForComplete(completed)
  }

  @Benchmark
  def zioBenchmark(): Unit = {
    val completed = new AtomicInteger(0)
    val runtime = Runtime.default
    (1 to tasks).foreach { _ =>
      val zio = ZIO.succeed(simpleComputation).map(_ => completed.incrementAndGet())
      Unsafe.unsafe(implicit u => runtime.unsafe.fork(zio))
    }
    waitForComplete(completed)
  }

  @Benchmark
  def rapidBenchmark(): Unit = {
    val completed = new AtomicInteger(0)
    (1 to tasks).foreach { _ =>
      Task(simpleComputation).map(_ => completed.incrementAndGet()).start()
    }
    waitForComplete(completed)
  }
}
