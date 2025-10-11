package rapid.v2

import sourcecode.{Enclosing, File, Line}

import java.util
import java.util.concurrent.{Executors, ScheduledExecutorService, ThreadFactory}
import scala.annotation.tailrec
import scala.concurrent.duration.FiniteDuration
import scala.util.Try

// Run benchmarks!
// Interrupt / Cancel support
object Test2 {
  def main(args: Array[String]): Unit = {
    /*val task = Task
      .pure("Testing")
      .map(_.length)
      .map(_ * 30)
      .map[Int](_ => throw new RuntimeException("Failure!"))
      .map(_ * 100)
    println("Run...")
    val fiber = task.start()
    val result = fiber.sync()
    println(s"Result: $result")*/

    /*val start = System.currentTimeMillis()

    def add(i: Int): Task[Int] = if (i == 100_000_000) {
      Task.pure(i)
//    } else if (i == 900_000) {
//      throw new RuntimeException("FAIL!")
    } else {
      Task.pure(i + 1).flatMap(add)
    }

    val counter = Task.pure(0).flatMap(add)
    val fiber = counter.start.sync()
    val value = fiber.sync()
    println(s"Value: $value, Time: ${System.currentTimeMillis() - start}")

    def addOriginal(i: Int): rapid.Task[Int] = if (i == 100_000_000) {
      rapid.Task.pure(i)
    } else {
      rapid.Task.pure(i + 1).flatMap(addOriginal)
    }

    val start2 = System.currentTimeMillis()
    val counter2 = rapid.Task.pure(0).flatMap(addOriginal)
    val fiber2 = counter2.start.sync()
    val value2 = fiber2.sync()
    println(s"Value2: $value2, Time: ${System.currentTimeMillis() - start2}")*/
  }
}