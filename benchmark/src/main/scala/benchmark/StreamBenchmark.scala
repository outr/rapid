package benchmark

import cats.effect.kernel.{Deferred, Fiber, Poll, Ref, Unique}
import cats.effect.{Concurrent, IO}
import cats.effect.unsafe.implicits.global
import org.openjdk.jmh.annotations._
import rapid.Task
import rapid.cats._
import rapid.trace.Trace

import scala.concurrent.duration._
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Thread)
class StreamBenchmark {
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
  def rapidStreamToList(): List[Int] = {
    verify(rapidStream.toList.sync())
  }

  @Benchmark
  def fs2StreamToList(): List[Int] = {
    verify(fs2Stream.compile.toList.unsafeRunSync())
  }

  @Benchmark
  def rapidStreamFilter(): List[Int] = {
    verify(rapidStream.filter(_ % 2 == 0).toList.sync(), size / 2)
  }

  @Benchmark
  def fs2StreamFilter(): List[Int] = {
    verify(fs2Stream.filter(_ % 2 == 0).compile.toList.unsafeRunSync(), size / 2)
  }

  @Benchmark
  def rapidStreamMap(): List[Int] = {
    verify(rapidStream.map(_ * 2).toList.sync())
  }

  @Benchmark
  def fs2StreamMap(): List[Int] = {
    verify(fs2Stream.map(_ * 2).compile.toList.unsafeRunSync())
  }

  @Benchmark
  def rapidParForeach(): Long = {
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
}