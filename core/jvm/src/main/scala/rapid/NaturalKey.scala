package rapid

import java.nio.file.Path
import java.util.Locale

object NaturalKey {
  sealed trait Part
  final case class Txt(s: String) extends Part
  final case class Num(n: BigInt, width: Int) extends Part

  final case class Key(parts: Vector[Part])

  private val Chunk = "([0-9]+|[^0-9]+)".r

  def of(path: Path): Key = of(path.getFileName.toString)
  def of(name: String): Key = {
    val parts = Chunk.findAllIn(name).map { chunk =>
      if (chunk.head.isDigit) Num(BigInt(chunk), chunk.length)
      else Txt(chunk.toLowerCase(Locale.ROOT))
    }.toVector
    Key(parts)
  }

  private val partOrd: Ordering[Part] = new Ordering[Part] {
    def compare(a: Part, b: Part): Int = (a, b) match {
      case (Txt(x), Txt(y))                 => x.compareTo(y)
      case (Num(x, wx), Num(y, wy))         =>
        val c = x.compare(y)
        if (c != 0) c else java.lang.Integer.compare(wx, wy) // tie-break leading zeros
      case (Txt(_), Num(_, _))              => -1
      case (Num(_, _), Txt(_))              => 1
    }
  }

  implicit val keyOrdering: Ordering[Key] = new Ordering[Key] {
    def compare(a: Key, b: Key): Int = {
      val as = a.parts; val bs = b.parts
      val min = math.min(as.length, bs.length)
      var i = 0
      var c = 0
      while (i < min && c == 0) {
        c = partOrd.compare(as(i), bs(i))
        i += 1
      }
      if (c != 0) c else java.lang.Integer.compare(as.length, bs.length)
    }
  }
}