package rapid

object UniqueDefaults {
  lazy val LettersLower = "abcdefghijklmnopqrstuvwxyz"
  lazy val LettersUpper = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
  lazy val Numbers = "0123456789"
  lazy val Readable = "ABCDEFGHJKLMNPQRSTWXYZ23456789"
  lazy val Hexadecimal = s"${Numbers}abcdef"
  lazy val LettersAndNumbers = s"$LettersLower$Numbers"
  lazy val AllLetters = s"$LettersLower$LettersUpper"
  lazy val AllLettersAndNumbers = s"$LettersLower$LettersUpper$Numbers"

  /** Random number generator; uses platform default (e.g. ThreadLocalRandom on JVM). */
  def random: Forge[Int, Int] = Platform.defaultRandom

  /** Default length for unique values. Defaults to 32. */
  def length: Int = 32

  /** Default characters for unique values. Defaults to AllLettersAndNumbers. */
  def characters: String = AllLettersAndNumbers
}
