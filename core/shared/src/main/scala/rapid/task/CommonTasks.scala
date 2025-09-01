package rapid.task

import rapid.Task

/**
 * Pre-allocated singleton instances for common task values.
 * Avoids millions of allocations for frequently used values.
 */
object CommonTasks {
  // Pre-allocated PureTask instances for common values
  val PURE_ZERO: PureTask[Int] = PureTask(0)
  val PURE_ONE: PureTask[Int] = PureTask(1)
  val PURE_MINUS_ONE: PureTask[Int] = PureTask(-1)
  val PURE_TRUE: PureTask[Boolean] = PureTask(true)
  val PURE_FALSE: PureTask[Boolean] = PureTask(false)
  val PURE_EMPTY_STRING: PureTask[String] = PureTask("")
  val PURE_NULL: PureTask[Null] = PureTask(null)
  
  // Pre-allocated SingleTask for common computations
  val SINGLE_ZERO: SingleTask[Int] = SingleTask(() => 0)
  val SINGLE_ONE: SingleTask[Int] = SingleTask(() => 1)
  
  // Reusable function objects (stateless)
  val IDENTITY_FUNCTION: Any => Any = (x: Any) => x
  val TO_STRING_FUNCTION: Any => String = (x: Any) => x.toString
  val INCREMENT_INT: Int => Int = (x: Int) => x + 1
  val DECREMENT_INT: Int => Int = (x: Int) => x - 1
  
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
    if (value) PURE_TRUE else PURE_FALSE
  }
  
  // Pre-allocated common exceptions
  lazy val ERROR_NULL_POINTER: ErrorTask[Nothing] = ErrorTask(new NullPointerException())
  lazy val ERROR_INDEX_OUT_OF_BOUNDS: ErrorTask[Nothing] = ErrorTask(new IndexOutOfBoundsException())
  lazy val ERROR_ILLEGAL_ARGUMENT: ErrorTask[Nothing] = ErrorTask(new IllegalArgumentException())
  lazy val ERROR_ILLEGAL_STATE: ErrorTask[Nothing] = ErrorTask(new IllegalStateException())
  lazy val ERROR_UNSUPPORTED_OPERATION: ErrorTask[Nothing] = ErrorTask(new UnsupportedOperationException())
  
  /**
   * Reusable empty collections
   */
  val PURE_EMPTY_LIST: PureTask[List[Nothing]] = PureTask(List.empty)
  val PURE_EMPTY_VECTOR: PureTask[Vector[Nothing]] = PureTask(Vector.empty)
  val PURE_EMPTY_MAP: PureTask[Map[Nothing, Nothing]] = PureTask(Map.empty)
  val PURE_EMPTY_SET: PureTask[Set[Nothing]] = PureTask(Set.empty)
  val PURE_EMPTY_OPTION: PureTask[Option[Nothing]] = PureTask(None)
  
  /**
   * Common Option values
   */
  val PURE_SOME_TRUE: PureTask[Option[Boolean]] = PureTask(Some(true))
  val PURE_SOME_FALSE: PureTask[Option[Boolean]] = PureTask(Some(false))
  
  /**
   * Common numeric values beyond -128 to 127
   */
  val PURE_MAX_INT: PureTask[Int] = PureTask(Int.MaxValue)
  val PURE_MIN_INT: PureTask[Int] = PureTask(Int.MinValue)
  val PURE_THOUSAND: PureTask[Int] = PureTask(1000)
  val PURE_MILLION: PureTask[Int] = PureTask(1000000)
  
  /**
   * Common Try wrappers for attempt()
   */
  import scala.util.{Try, Success, Failure}
  
  val TRY_SUCCESS_UNIT: Try[Unit] = Success(())
  val TRY_SUCCESS_TRUE: Try[Boolean] = Success(true)
  val TRY_SUCCESS_FALSE: Try[Boolean] = Success(false)
  val TRY_SUCCESS_ZERO: Try[Int] = Success(0)
  val TRY_SUCCESS_ONE: Try[Int] = Success(1)
  val TRY_SUCCESS_EMPTY_STRING: Try[String] = Success("")
  val TRY_SUCCESS_NONE: Try[Option[Nothing]] = Success(None)
  
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
    case TRY_SUCCESS_UNIT => PureTask(TRY_SUCCESS_UNIT).asInstanceOf[PureTask[Try[T]]]
    case TRY_SUCCESS_TRUE => PureTask(TRY_SUCCESS_TRUE).asInstanceOf[PureTask[Try[T]]]
    case TRY_SUCCESS_FALSE => PureTask(TRY_SUCCESS_FALSE).asInstanceOf[PureTask[Try[T]]]
    case TRY_SUCCESS_ZERO => PureTask(TRY_SUCCESS_ZERO).asInstanceOf[PureTask[Try[T]]]
    case TRY_SUCCESS_ONE => PureTask(TRY_SUCCESS_ONE).asInstanceOf[PureTask[Try[T]]]
    case Success(i: Int) if i >= -128 && i <= 127 => 
      PureTask(tryInt(i)).asInstanceOf[PureTask[Try[T]]]
    case _ => PureTask(value)
  }
}