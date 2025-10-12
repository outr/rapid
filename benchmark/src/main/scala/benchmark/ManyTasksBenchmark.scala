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

  // Reuse ZIO runtime; creating it is expensive
  private lazy val zioRuntime = Runtime.default

  @Benchmark
  def ioBenchmark(): Unit = {
    val latch = new CountDownLatch(tasks)
    var i = 0
    while (i < tasks) {
      val n = 163 + i // avoid constant-folding
      val fiberSpawn =
        IO(n.toDouble)
          .map(math.sqrt)
          .map(math.round)
          .map(_.toInt)
          .guarantee(IO(latch.countDown())) // run on completion
          .start // fork a lightweight fiber

      fiberSpawn.unsafeRunAndForget() // only starts the fork, not the work inline
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
      val n = 163 + i
      val z =
        ZIO
          .attempt(math.round(math.sqrt(n.toDouble)).toInt)
          .ensuring(ZIO.succeed(latch.countDown()))
      Unsafe.unsafe { implicit u => zioRuntime.unsafe.fork(z) } // fork fiber
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
      val n = 163 + i
      Task(math.round(math.sqrt(n.toDouble)).toInt)
        .map(_ => latch.countDown())
        .start()
      i += 1
    }
    latch.await()
    assert(i == tasks)
  }
}
