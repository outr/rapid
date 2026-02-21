package benchmark

import cats.effect.kernel.{Deferred, Fiber, Poll, Ref, Unique}
import cats.effect.{Concurrent, IO}
import cats.effect.unsafe.implicits.global
import org.openjdk.jmh.annotations._
import rapid.*
import rapid.cats._
import rapid.trace.Trace

import scala.concurrent.duration._
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Thread)
class ParallelStreamBenchmark {
  @Param(Array("1000", "10000")) //, "100000"))
  var size: Int = _

  lazy val rapidStream: rapid.Stream[Int] = rapid.Stream.emits(1 to size)
  lazy val fs2Stream: fs2.Stream[IO, Int] = fs2.Stream.emits(1 to size)

  @Setup(Level.Trial)
  def setup(): Unit = {
    Trace.Enabled = false
    rapidStream
    fs2Stream
  }

  private def verify(list: List[Int], expected: Int = size): List[Int] = {
    assert(list.size == expected, s"Size was ${list.size} but expected $expected")
    list
  }

  @Benchmark
  def rapidVirtualParallelStreamToList(): List[Int] = {
    Task.Virtual = true
    verify(rapidStream.par(32)(Task.pure(_)).toList.sync())
  }

  @Benchmark
  def rapidFixedParallelStreamToList(): List[Int] = {
    Task.Virtual = false
    verify(rapidStream.par(32)(Task.pure(_)).toList.sync())
  }

  @Benchmark
  def rapidVirtualParallelStreamFilter(): List[Int] = {
    Task.Virtual = true
    verify(rapidStream.filter(_ % 2 == 0).par(8)(Task.pure(_)).toList.sync(), size / 2)
  }

  @Benchmark
  def rapidFixedParallelStreamFilter(): List[Int] = {
    Task.Virtual = false
    verify(rapidStream.filter(_ % 2 == 0).par(8)(Task.pure(_)).toList.sync(), size / 2)
  }

  @Benchmark
  def rapidVirtualParallelStreamMap(): List[Int] = {
    Task.Virtual = true
    verify(rapidStream.par(32)(i => Task(i * 2)).toList.sync())
  }

  @Benchmark
  def rapidFixedParallelStreamMap(): List[Int] = {
    Task.Virtual = false
    verify(rapidStream.par(32)(i => Task(i * 2)).toList.sync())
  }

  @Benchmark
  def fs2ParallelStreamMap(): List[Int] = {
    verify(fs2Stream.parEvalMap(32)(i => IO(i * 2)).compile.toList.unsafeRunSync())
  }

  @Benchmark
  def rapidVirtualParForeach(): Long = {
    Task.Virtual = true
    // Effect-only: side-effect into an AtomicLong, no allocation of results
    val add = new AtomicLong(0L)
    rapidStream.parForeach(32) { i =>
      add.addAndGet(i.toLong)
      Task.unit
    }.sync()
    val s = add.get()
    // Verify correctness: sum(1..size)
    val expected = (size.toLong * (size.toLong + 1L)) / 2L
    assert(s == expected, s"parForeach sum $s != expected $expected")
    s
  }

  @Benchmark
  def rapidFixedParForeach(): Long = {
    Task.Virtual = false
    // Effect-only: side-effect into an AtomicLong, no allocation of results
    val add = new AtomicLong(0L)
    rapidStream.parForeach(32) { i =>
      add.addAndGet(i.toLong)
      Task.unit
    }.sync()
    val s = add.get()
    // Verify correctness: sum(1..size)
    val expected = (size.toLong * (size.toLong + 1L)) / 2L
    assert(s == expected, s"parForeach sum $s != expected $expected")
    s
  }

  @Benchmark
  def rapidVirtualParFoldSum(): Long = {
    Task.Virtual = true
    val s = rapidStream
      .parFold(0L, threads = 32)((acc, r) => Task.pure(acc + r.toLong), _ + _)
      .sync()
    val expected = (size.toLong * (size.toLong + 1L)) / 2L
    assert(s == expected, s"parFold sum $s != expected $expected")
    s
  }

  @Benchmark
  def rapidFixedParFoldSum(): Long = {
    Task.Virtual = false
    val s = rapidStream
      .parFold(0L, threads = 32)((acc, r) => Task.pure(acc + r.toLong), _ + _)
      .sync()
    val expected = (size.toLong * (size.toLong + 1L)) / 2L
    assert(s == expected, s"parFold sum $s != expected $expected")
    s
  }

  @Benchmark
  def rapidVirtualParallelStreamFoldSum(): Long = {
    Task.Virtual = true
    val s = rapidStream
      .par(maxThreads = 32) { i => Task.pure(i) }
      .fold(0L) { (acc, r) => Task.pure(acc + r.toLong) }
      .sync()
    val expected = (size.toLong * (size.toLong + 1L)) / 2L
    assert(s == expected, s"parallelStreamFold sum $s != expected $expected")
    s
  }

  @Benchmark
  def rapidFixedParallelStreamFoldSum(): Long = {
    Task.Virtual = false
    val s = rapidStream
      .par(maxThreads = 32) { i => Task.pure(i) }
      .fold(0L) { (acc, r) => Task.pure(acc + r.toLong) }
      .sync()
    val expected = (size.toLong * (size.toLong + 1L)) / 2L
    assert(s == expected, s"parallelStreamFold sum $s != expected $expected")
    s
  }

  @Benchmark
  def fs2ParEvalMapSum(): Long = {
    import _root_.cats.effect.IO
    val ref = new java.util.concurrent.atomic.AtomicLong(0L)
    fs2Stream.parEvalMap(32) { i =>
      IO { ref.addAndGet(i.toLong) }
    }.compile.drain.unsafeRunSync()
    val s = ref.get()
    val expected = (size.toLong * (size.toLong + 1L)) / 2L
    assert(s == expected)
    s
  }
}
