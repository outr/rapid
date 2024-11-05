package benchmark

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.openjdk.jmh.annotations.{Benchmark, BenchmarkMode, Mode, OutputTimeUnit, Scope, State}
import zio.{Duration, Runtime, Unsafe, ZIO}

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.DurationInt

// jmh:run -i 3 -wi 3 -f1 -t1 -rf JSON -rff benchmarks.json
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
class HeavyLoadBenchmark {
  private val tasks = 1_000
  private val sleepTime = 5.seconds

  private def waitForComplete(completed: AtomicInteger): Unit = {
    while (completed.get() != tasks) {
      println(s"Completed: ${completed.get()}")
      Thread.sleep(5000)
    }
    println("COMPLETE!")
  }

  @Benchmark
  def ioBenchmark(): Unit = {
    val completed = new AtomicInteger(0)
    (1 to tasks).foreach { _ =>
      IO.sleep(sleepTime).map(_ => completed.incrementAndGet()).unsafeRunAndForget()
    }
    waitForComplete(completed)
  }

  @Benchmark
  def zioBenchmark(): Unit = {
    val completed = new AtomicInteger(0)
    val runtime = Runtime.default
    (1 to tasks).foreach { _ =>
      val zio = ZIO.sleep(Duration.fromScala(sleepTime)).map(_ => completed.incrementAndGet())
      Unsafe.unsafe(implicit u => runtime.unsafe.fork(zio))
    }
    waitForComplete(completed)
  }

  @Benchmark
  def rapidBenchmark(): Unit = {
    ???
  }
}
