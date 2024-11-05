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
  private val iterations = 100_000
//  private val iterations = 10
  private val expected = 1_300_000

  // Simple computation to benchmark
  private def simpleComputation: Int = math.round(math.sqrt(163.0)).toInt

/*  @Benchmark
  def directBenchmark(): Unit = {
    var result = 0
    for (_ <- 1 to iterations) {
      result += simpleComputation
    }
    assert(result == expected)
  }*/

  private def verify(name: String, result: Int): Unit = {
    if (result != expected) {
      println(s"$name - Expected: $expected, Got: $result")
//      sys.exit(1)
    }
  }

  /*@Benchmark
  def ioBenchmark(): Unit = {
    val io = (1 to iterations).foldLeft(IO.pure(0))((io, i) => io.flatMap { total =>
      IO(total + simpleComputation)
    })
    val result = io.unsafeRunSync()
    verify("cats-effect", result)
  }

  @Benchmark
  def zioBenchmark(): Unit = {
    val zio = (1 to iterations).foldLeft(ZIO.succeed(0))((t, i) => t.flatMap { total =>
      ZIO.succeed(total + simpleComputation)
    })
    val runtime = Runtime.default
    val result = Unsafe.unsafe(implicit u => runtime.unsafe.run(zio).getOrThrowFiberFailure())
    verify("ZIO", result)
  }*/

  @Benchmark
  def rapidBenchmark(): Unit = {
    val task = (1 to iterations).foldLeft(Task(0))((t, i) => t.flatMap { total =>
      Task(total + simpleComputation)
    })
    val result = task.await()
    println(s"Result: $result")
    verify("Rapid", result)
  }
}