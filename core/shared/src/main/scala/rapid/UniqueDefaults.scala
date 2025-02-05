package rapid

import java.util.concurrent.ThreadLocalRandom

object UniqueDefaults {
  lazy val LettersLower = "abcdefghijklmnopqrstuvwxyz"
  lazy val LettersUpper = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
  lazy val Numbers = "0123456789"
  lazy val Readable = "ABCDEFGHJKLMNPQRSTWXYZ23456789"
  lazy val Hexadecimal = s"${Numbers}abcdef"
  lazy val LettersAndNumbers = s"$LettersLower$Numbers"
  lazy val AllLetters = s"$LettersLower$LettersUpper"
  lazy val AllLettersAndNumbers = s"$LettersLower$LettersUpper$Numbers"

  /**
   * Random number generator used to generate unique values. Defaults to `threadLocalRandom`.
   */
  var random: Forge[Int, Int] = threadLocalRandom

  /**
   * The default length to use for generating unique values. Defaults to 32.
   */
  var length: Int = 32

  /**
   * The default characters to use for generating unique values. Defaults to AllLettersAndNumbers.
   */
  var characters: String = AllLettersAndNumbers

  /**
   * Uses java.util.concurrent.ThreadLocalRandom to generate random numbers.
   *
   * @param max the maximum value to include
   * @return random number between 0 and max
   */
  final lazy val threadLocalRandom: Forge[Int, Int] = Forge { max =>
    Task(ThreadLocalRandom.current().nextInt(max))
  }
}
