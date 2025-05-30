package spec

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import rapid._

class MaterializedCursorSpec extends AnyWordSpec with Matchers {
  "materializedCursorEvalMap" should {
    "emit all elements unchanged when cursor just adds" in {
      val stream = Stream(1, 2, 3, 4)
      val result = stream.materializedCursorEvalMap[Int, Int] { (next, cursor) =>
        Task.pure(cursor.add(next))
      }.toList.sync()
      result shouldEqual List(1, 2, 3, 4)
    }
    "filter out even numbers using cursor" in {
      val stream = Stream(1, 2, 3, 4, 5, 6)
      val result = stream.materializedCursorEvalMap[Int, Int] { (next, cursor) =>
        if (next % 2 == 0) Task.pure(cursor) else Task.pure(cursor.add(next))
      }.toList.sync()
      result shouldEqual List(1, 3, 5)
    }
    "modify the last emitted value if current is negative" in {
      val stream = Stream(1, 2, -1, 3)
      val result = stream.materializedCursorEvalMap[Int, Int] { (next, cursor) =>
        if (next < 0)
          cursor.modifyPrevious(1)(v => Task.pure(Some(v * 10)))
        else
          Task.pure(cursor.add(next))
      }.toList.sync()
      result shouldEqual List(1, 20, 3)
    }
    "delete the last element if next is zero" in {
      val stream = Stream(5, 6, 0, 7)
      val result = stream.materializedCursorEvalMap[Int, Int] { (next, cursor) =>
        if (next == 0)
          cursor.deletePrevious(1)
        else
          Task.pure(cursor.add(next))
      }.toList.sync()
      result shouldEqual List(5, 7)
    }
    "support chaining modifications to earlier values" in {
      val stream = Stream(10, 20, 30)
      val result = stream.materializedCursorEvalMap[Int, Int] { (next, cursor) =>
        val updated = if (cursor.size >= 2)
          cursor.modifyPrevious(2)(x => Task.pure(Some(x + next)))
        else
          Task.pure(cursor)
        updated.map(_.add(next))
      }.toList.sync()
      result shouldEqual List(40, 20, 30)
    }
    "transform number words to integers using case-insensitive lookup" in {
      val wordToNumber = Map(
        "one" -> 1,
        "two" -> 2,
        "three" -> 3,
        "four" -> 4,
        "five" -> 5
      )
      val stream = Stream("One", "Two", "Three", "Unknown", "four", "Remove Previous", "FIVE")
      val result = stream.materializedCursorEvalMap[String, Int] { (next, cursor) =>
        val n = next.toLowerCase
        wordToNumber.get(n) match {
          case Some(n) => Task.pure(cursor.add(n))
          case None if n == "remove previous" => cursor.deletePrevious(1)
          case None => Task.pure(cursor)
        }
      }.toList.sync()
      result shouldEqual List(1, 2, 3, 5)
    }
    "emit length of each string after filtering short words" in {
      val stream = Stream("a", "bb", "ccc", "dddd")
      val result = stream.materializedCursorEvalMap[String, Int] { (next, cursor) =>
        if (next.length >= 3)
          Task.pure(cursor.add(next.length))
        else
          Task.pure(cursor)
      }.toList.sync()
      result shouldEqual List(3, 4)
    }
    "access previous element by index" in {
      val stream = Stream(10, 20, 30)
      val result = stream.materializedCursorEvalMap[Int, Int] { (next, cursor) =>
        if (cursor.size == 2 && cursor.previous(2).contains(10))
          Task.pure(cursor.add(next + 100))
        else
          Task.pure(cursor.add(next))
      }.toList.sync()
      result shouldEqual List(10, 20, 130)
    }
    "apply a pure transformation to a previous element" in {
      val stream = Stream(1, 2, 3)
      val result = stream.materializedCursorEvalMap[Int, Int] { (next, cursor) =>
        val updated = if (next == 3) Task.pure(cursor.previousMap(1)(_ * 10))
        else Task.pure(cursor)
        updated.map(_.add(next))
      }.toList.sync()
      result shouldEqual List(1, 20, 3)
    }
    "apply effectful transformation to entire cursor history" in {
      val stream = Stream(1, 2, 3)
      val result = stream.materializedCursorEvalMap[Int, Int](
        f = (next, cursor) => Task.pure(cursor.add(next)),
        handleEnd = cursor => cursor.mapHistory(x => Task(x * 2))
      ).toList.sync()

      result shouldEqual List(2, 4, 6)
    }
    "find a previous value by predicate" in {
      val stream = Stream(1, 2, 3, 4)
      val result = stream.materializedCursorEvalMap[Int, Int] { (next, cursor) =>
        val modified = cursor.findPrevious(_ % 2 == 0) match {
          case Some(even) if next == 4 => cursor.add(even * 100)
          case _ => cursor.add(next)
        }
        Task.pure(modified)
      }.toList.sync()
      result shouldEqual List(1, 2, 3, 200)
    }
    "manipulate next with history" in {
      val stream = Stream(10, 20, 30, 40)
      val result = stream.materializedCursorEvalMap[Int, Int] { (next, cursor) =>
        Task.pure(cursor.add(cursor.history.sum + next))
      }.toList.sync()
      result shouldEqual List(10, 30, 70, 150)
    }
    "remove previously added elements that don't match a predicate" in {
      val stream = Stream(1, 2, 3, 4)
      val result = stream.materializedCursorEvalMap[Int, Int] { (next, cursor) =>
        val updated = cursor.add(next)
        Task.pure(updated.filter(_ % 2 == 0))
      }.toList.sync()
      result shouldEqual List(2, 4)
    }
    "clear the entire cursor after a trigger word" in {
      val stream = Stream(1, 2, 999, 3)
      val result = stream.materializedCursorEvalMap[Int, Int] { (next, cursor) =>
        if (next == 999)
          cursor.clear()
        else
          Task.pure(cursor.add(next))
      }.toList.sync()
      result shouldEqual List(3)
    }
  }
}