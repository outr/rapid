package benchmark

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.openjdk.jmh.annotations._
import rapid.v2.Test2
import rapid.{Task, Test3}
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
    assert(i == tasks)
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
    assert(i == tasks)
  }

  @Benchmark
  def rapidBenchmark(): Unit = {
    val latch = new CountDownLatch(tasks)
    var i = 0
    while (i < tasks) {
      Task(simpleComputation).map(_ => latch.countDown()).start()
      i += 1
    }
    latch.await()
    assert(i == tasks)
  }

  @Benchmark
  def rapid2Benchmark(): Unit = {
    val latch = new CountDownLatch(tasks)
    var i = 0
    while (i < tasks) {
      rapid.v2.Task(simpleComputation).map(_ => latch.countDown()).start()
      i += 1
    }
    latch.await()
    assert(i == tasks)
  }
}
