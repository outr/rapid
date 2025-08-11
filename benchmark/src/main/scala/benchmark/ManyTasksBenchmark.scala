package benchmark

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.openjdk.jmh.annotations._
import rapid.Task
import zio.{Runtime, Unsafe, ZIO}

import java.util.concurrent.{CountDownLatch, TimeUnit}

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
class ManyTasksBenchmark {
  private val tasks = 1_000_000
  private def simpleComputation: Int = math.round(math.sqrt(163.0)).toInt

  // Reuse ZIO runtime; creating it is expensive
  private lazy val zioRuntime = Runtime.default

  @Benchmark
  def ioBenchmark(): Unit = {
    val latch = new CountDownLatch(tasks)
    var i = 0
    while (i < tasks) {
      IO(simpleComputation)
        .map(_ => latch.countDown())
        .unsafeRunAndForget()
      i += 1
    }
    latch.await()
  }

  @Benchmark
  def zioBenchmark(): Unit = {
    val latch = new CountDownLatch(tasks)
    var i = 0
    while (i < tasks) {
      val z = ZIO.attempt(simpleComputation).map(_ => latch.countDown())
      Unsafe.unsafe { implicit u => zioRuntime.unsafe.fork(z) }
      i += 1
    }
    latch.await()
  }

  @Benchmark
  def rapidBenchmark(): Unit = {
    val latch = new CountDownLatch(tasks)
    var i = 0
    while (i < tasks) {
      Task(simpleComputation).map(_ => latch.countDown()).startAndForget()
      i += 1
    }
    latch.await()
  }
}
