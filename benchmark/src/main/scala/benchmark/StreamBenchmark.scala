package benchmark

import cats.effect.kernel.{Deferred, Fiber, Poll, Ref, Unique}
import cats.effect.{Concurrent, IO}
import cats.effect.unsafe.implicits.global
import org.openjdk.jmh.annotations._
import rapid.Task
import rapid.cats._

import scala.concurrent.duration._
import java.util.concurrent.TimeUnit

@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Thread)
class StreamBenchmark {
  @Param(Array("1000")) //, "10000", "100000"))
  var size: Int = _

  lazy val rapidStream: rapid.Stream[Int] = rapid.Stream.fromList((1 to size).toList)
  lazy val rapidFs2Stream: fs2.Stream[Task, Int] = fs2.Stream.emits(1 to size)
  lazy val fs2Stream: fs2.Stream[IO, Int] = fs2.Stream.emits(1 to size)

  @Setup(Level.Trial)
  def setup(): Unit = {
    rapidStream
    fs2Stream
  }

  @Benchmark
  def rapidStreamToList(): List[Int] = {
    rapidStream.toList.sync()
  }

  @Benchmark
  def rapidFs2StreamToList(): List[Int] = {
    rapidFs2Stream.compile.toList.sync()
  }

//  @Benchmark
//  def fs2StreamToList(): List[Int] = {
//    fs2Stream.compile.toList.unsafeRunSync()
//  }

  @Benchmark
  def rapidStreamFilter(): List[Int] = {
    rapidStream.filter(_ % 2 == 0).toList.sync()
  }

//  @Benchmark
//  def fs2StreamFilter(): List[Int] = {
//    fs2Stream.filter(_ % 2 == 0).compile.toList.unsafeRunSync()
//  }

  @Benchmark
  def rapidStreamMap(): List[Int] = {
    rapidStream.map(_ * 2).toList.sync()
  }

//  @Benchmark
//  def fs2StreamMap(): List[Int] = {
//    fs2Stream.map(_ * 2).compile.toList.unsafeRunSync()
//  }
}