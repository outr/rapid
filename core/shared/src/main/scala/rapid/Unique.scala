package rapid

import rapid.task.Taskable

class Unique(val length: Int,
             val characters: String,
             val random: Forge[Int, Int]) extends Taskable[String] {
  override def toTask: Task[String] = Task {
    val charMax = characters.length
    (0 until length).map(_ => random(charMax)).tasks.map(_.map(characters.charAt).mkString)
  }.flatten

  def withLength(length: Int): Unique = modify(length = length)
  def withCharacters(characters: String): Unique = modify(characters = characters)
  def withRandom(forge: Forge[Int, Int]): Unique = modify(random = random)

  def modify(length: Int = length,
             characters: String = characters,
             random: Forge[Int, Int] = random): Unique = new Unique(
    length = length,
    characters = characters,
    random = random
  )
}

/**
 * Unique String generator
 */
object Unique extends Unique(UniqueDefaults.length, UniqueDefaults.characters, UniqueDefaults.random) {
  import UniqueDefaults._

  /**
   * Convenience functionality to generate a UUID (https://en.wikipedia.org/wiki/Universally_unique_identifier)
   *
   * 32 characters of unique hexadecimal values with dashes representing 36 total characters
   */
  def uuid: Task[String] = Task {
    val a = modify(length = 8, characters = Hexadecimal).sync()
    val b = modify(length = 4, characters = Hexadecimal).sync()
    val c = modify(length = 3, characters = Hexadecimal).sync()
    val d = modify(length = 1, characters = "89ab").sync()
    val e = modify(length = 3, characters = Hexadecimal).sync()
    val f = modify(length = 12, characters = Hexadecimal).sync()
    s"$a-$b-4$c-$d$e-$f"
  }

  /**
   * Returns the number of possible values for a specific length and characters.
   */
  def poolSize(length: Int = 32, characters: String = AllLettersAndNumbers): Task[Long] = Task {
    math.pow(characters.length, length).toLong
  }
}