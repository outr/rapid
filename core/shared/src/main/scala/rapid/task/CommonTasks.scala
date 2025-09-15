package rapid.task

import rapid.Task

/**
 * Pre-allocated singleton instances for common task values.
 * Avoids millions of allocations for frequently used values.
 */
object CommonTasks {
  // Pre-allocated PureTask instances for common values
  val pureZero: PureTask[Int] = PureTask(0)
  val pureOne: PureTask[Int] = PureTask(1)
  val pureMinusOne: PureTask[Int] = PureTask(-1)
  val pureTrue: PureTask[Boolean] = PureTask(true)
  val pureFalse: PureTask[Boolean] = PureTask(false)
  val pureEmptyString: PureTask[String] = PureTask("")
  val pureNull: PureTask[Null] = PureTask(null)
  
  // Pre-allocated SingleTask for common computations
  val singleZero: SingleTask[Int] = SingleTask(() => 0)
  val singleOne: SingleTask[Int] = SingleTask(() => 1)
  
  // Reusable function objects (stateless)
  val identityFunction: Any => Any = (x: Any) => x
  val toStringFunction: Any => String = (x: Any) => x.toString
  val incrementInt: Int => Int = (x: Int) => x + 1
  val decrementInt: Int => Int = (x: Int) => x - 1
  
  /**
   * Get a cached PureTask for small integers (-128 to 127)
   * Similar to Integer cache in JVM
   */
  private val intCache: Array[PureTask[Int]] = Array.tabulate(256)(i => PureTask(i - 128))
  
  def pureInt(value: Int): PureTask[Int] = {
    if (value >= -128 && value <= 127) {
      intCache(value + 128)
    } else {
      PureTask(value)
    }
  }
  
  /**
   * Get a cached PureTask for boolean values
   */
  def pureBoolean(value: Boolean): PureTask[Boolean] = {
    if (value) pureTrue else pureFalse
  }
  
  // Pre-allocated common exceptions
  lazy val errorNullPointer: ErrorTask[Nothing] = ErrorTask(new NullPointerException())
  lazy val errorIndexOutOfBounds: ErrorTask[Nothing] = ErrorTask(new IndexOutOfBoundsException())
  lazy val errorIllegalArgument: ErrorTask[Nothing] = ErrorTask(new IllegalArgumentException())
  lazy val errorIllegalState: ErrorTask[Nothing] = ErrorTask(new IllegalStateException())
  lazy val errorUnsupportedOperation: ErrorTask[Nothing] = ErrorTask(new UnsupportedOperationException())
  
  /**
   * Reusable empty collections
   */
  val pureEmptyList: PureTask[List[Nothing]] = PureTask(List.empty)
  val pureEmptyVector: PureTask[Vector[Nothing]] = PureTask(Vector.empty)
  val pureEmptyMap: PureTask[Map[Nothing, Nothing]] = PureTask(Map.empty)
  val pureEmptySet: PureTask[Set[Nothing]] = PureTask(Set.empty)
  val pureEmptyOption: PureTask[Option[Nothing]] = PureTask(None)
  
  /**
   * Common Option values
   */
  val pureSomeTrue: PureTask[Option[Boolean]] = PureTask(Some(true))
  val pureSomeFalse: PureTask[Option[Boolean]] = PureTask(Some(false))
  
  /**
   * Common numeric values beyond -128 to 127
   */
  val pureMaxInt: PureTask[Int] = PureTask(Int.MaxValue)
  val pureMinInt: PureTask[Int] = PureTask(Int.MinValue)
  val pureThousand: PureTask[Int] = PureTask(1000)
  val pureMillion: PureTask[Int] = PureTask(1000000)
  
  /**
   * Common Try wrappers for attempt()
   */
  import scala.util.{Try, Success, Failure}
  
  val trySuccessUnit: Try[Unit] = Success(())
  val trySuccessTrue: Try[Boolean] = Success(true)
  val trySuccessFalse: Try[Boolean] = Success(false)
  val trySuccessZero: Try[Int] = Success(0)
  val trySuccessOne: Try[Int] = Success(1)
  val trySuccessEmptyString: Try[String] = Success("")
  val trySuccessNone: Try[Option[Nothing]] = Success(None)
  
  // Cache for small integer Success values
  private val tryIntCache: Array[Try[Int]] = Array.tabulate(256)(i => Success(i - 128))
  
  def tryInt(value: Int): Try[Int] = {
    if (value >= -128 && value <= 127) {
      tryIntCache(value + 128)
    } else {
      Success(value)
    }
  }
  
  /**
   * Get cached PureTask[Try[T]] for common values
   */
  def pureTry[T](value: Try[T]): PureTask[Try[T]] = value match {
    case `trySuccessUnit` => PureTask(trySuccessUnit).asInstanceOf[PureTask[Try[T]]]
    case `trySuccessTrue` => PureTask(trySuccessTrue).asInstanceOf[PureTask[Try[T]]]
    case `trySuccessFalse` => PureTask(trySuccessFalse).asInstanceOf[PureTask[Try[T]]]
    case `trySuccessZero` => PureTask(trySuccessZero).asInstanceOf[PureTask[Try[T]]]
    case `trySuccessOne` => PureTask(trySuccessOne).asInstanceOf[PureTask[Try[T]]]
    case Success(i: Int) if i >= -128 && i <= 127 => 
      PureTask(tryInt(i)).asInstanceOf[PureTask[Try[T]]]
    case _ => PureTask(value)
  }
}