package benchmark

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.openjdk.jmh.annotations.{Benchmark, BenchmarkMode, Mode, OutputTimeUnit, Scope, State}
import rapid.Task
import rapid.trace.Trace
import zio.{Duration, Runtime, Unsafe, ZIO}

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.DurationInt
import scala.util.Random

// jmh:run -i 3 -wi 3 -f1 -t1 -rf JSON -rff benchmarks.json
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
class ManySleepsBenchmark {
  private val tasks = 1_000_000
  private def sleepTime = Random.nextInt(10).seconds

  // Reuse ZIO runtime; creating it is expensive
  private lazy val zioRuntime = Runtime.default

  private def waitForComplete(completed: AtomicInteger): Unit = {
    while (completed.get() != tasks) {
      Thread.sleep(50)
    }
  }

  @Benchmark
  def ioBenchmark(): Unit = {
    Random.setSeed(123)
    val completed = new AtomicInteger(0)
    (1 to tasks).foreach { _ =>
      IO.sleep(sleepTime).map(_ => completed.incrementAndGet()).unsafeRunAndForget()
    }
    waitForComplete(completed)
  }

  @Benchmark
  def zioBenchmark(): Unit = {
    Random.setSeed(123)
    val completed = new AtomicInteger(0)
    (1 to tasks).foreach { _ =>
      val zio = ZIO.sleep(Duration.fromScala(sleepTime)).map(_ => completed.incrementAndGet())
      Unsafe.unsafe(implicit u => zioRuntime.unsafe.fork(zio))
    }
    waitForComplete(completed)
  }

  @Benchmark
  def rapidVirtualBenchmark(): Unit = {
    Trace.Enabled = false
    Task.Virtual = true
    Random.setSeed(123)
    val completed = new AtomicInteger(0)
    (1 to tasks).foreach { _ =>
      Task.sleep(sleepTime).map(_ => completed.incrementAndGet()).startUnit()
    }
    waitForComplete(completed)
  }

  @Benchmark
  def rapidFixedBenchmark(): Unit = {
    Trace.Enabled = false
    Task.Virtual = false
    Random.setSeed(123)
    val completed = new AtomicInteger(0)
    (1 to tasks).foreach { _ =>
      Task.sleep(sleepTime).map(_ => completed.incrementAndGet()).startUnit()
    }
    waitForComplete(completed)
  }
}
