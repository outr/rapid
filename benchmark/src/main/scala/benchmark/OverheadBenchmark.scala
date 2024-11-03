package benchmark

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.openjdk.jmh.annotations._
import rapid.Task
import zio.{Runtime, Unsafe, ZIO}

import java.util.concurrent.TimeUnit

// jmh:run -i 3 -wi 3 -f1 -t1 -rf JSON -rff benchmarks.json
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
class OverheadBenchmark {
  // Define the number of iterations
  private val iterations = 1_000_000

  // Simple computation to benchmark
  private def simpleComputation: Int = math.round(math.sqrt(163.0)).toInt

  @Benchmark
  def directBenchmark(): Int = {
    var result = 0
    for (_ <- 1 to iterations) {
      result += simpleComputation
    }
    result
  }

  @Benchmark
  def ioBenchmark(): Int = {
    var result = 0
    for (_ <- 1 to iterations) {
      result += IO.delay(simpleComputation).unsafeRunSync()
    }
    result
  }

  @Benchmark
  def zioBenchmark(): Int = {
    var result = 0
    val runtime = Runtime.default
    for (_ <- 1 to iterations) {
      result += Unsafe.unsafe(implicit u => runtime.unsafe.run(ZIO.attempt(simpleComputation)).getOrThrowFiberFailure())
    }
    result
  }

  @Benchmark
  def rapidBenchmark(): Int = {
    var result = 0
    val task = Task(simpleComputation)
    for (_ <- 1 to iterations) {
      result += task.await()
    }
    result
  }
}