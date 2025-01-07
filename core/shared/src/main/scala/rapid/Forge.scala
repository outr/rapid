package rapid

/**
 * A trait representing a factory for creating `Task` instances from an input value.
 *
 * `Forge` abstracts the concept of transforming an input of type `Input` into a `Task[Return]`,
 * enabling the creation of tasks dynamically based on provided inputs.
 *
 * @tparam Input the type of input used to produce a `Task[Return]`.
 * @tparam Return the type of the result produced by the `Task`.
 */
trait Forge[Input, Return] extends Any {
  /**
   * Creates a `Task[Return]` from the given input.
   *
   * @param input the input value used to generate the task.
   * @return a `Task[Return]` representing the asynchronous computation or work.
   */
  def apply(input: Input): Task[Return]

  def flatMap[R](that: Forge[Return, R]): Forge[Input, R] = Forge { input =>
    this(input).flatMap(that(_))
  }

  def map[R](that: Return => R): Forge[Input, R] = flatMap { input =>
    Task(that(input))
  }
}

/**
 * Companion object for the `Forge` trait, providing utility methods and a concrete implementation.
 */
object Forge {
  /**
   * A value class implementation of `Forge` backed by a function.
   *
   * @param f the function used to transform an input of type `Input` into a `Task[Return]`.
   * @tparam Input the type of input used to produce a `Task[Return]`.
   * @tparam Return the type of the result produced by the `Task`.
   */
  case class FunctionForge[Input, Return](f: Input => Task[Return])
    extends AnyVal with Forge[Input, Return] {

    /**
     * Creates a `Task[Return]` by applying the underlying function to the input.
     *
     * @param input the input value used to generate the task.
     * @return a `Task[Return]` created by applying the function `f` to the input.
     */
    override def apply(input: Input): Task[Return] = f(input)
  }

  /**
   * Creates a `Forge` instance from a given function.
   *
   * @param f the function used to transform an input of type `Input` into a `Task[Return]`.
   * @tparam Input the type of input used to produce a `Task[Return]`.
   * @tparam Return the type of the result produced by the `Task`.
   * @return a `Forge` instance wrapping the provided function.
   */
  def apply[Input, Return](f: Input => Task[Return]): Forge[Input, Return] = FunctionForge(f)
}
