package rapid

/**
 * A purely immutable, append-only cursor for building and post-processing streamed values.
 *
 * `Cursor` provides access to the full history of values produced so far in a materialized stream,
 * and allows effectful or pure modifications of previously emitted elements.
 *
 * Common use cases include:
 * - Deduplication and cleanup of earlier elements
 * - Post-hoc normalization or merging
 * - Retrospective tagging or enrichment
 *
 * @param history the ordered sequence of output elements collected so far
 * @tparam From the original input type that led to each `To` (used only for type clarity)
 * @tparam To the output value type tracked by the cursor
 */
case class Cursor[From, To](history: Vector[To]) {
  /** Returns the number of elements in the cursor history */
  def size: Int = history.size

  /** True if the cursor contains no elements */
  def isEmpty: Boolean = history.isEmpty

  /** True if the cursor contains one or more elements */
  def nonEmpty: Boolean = history.nonEmpty

  /**
   * Accesses the Nth element before the current point, where 1 = most recent.
   * @param i the number of steps back (1-based)
   * @return the corresponding historical element, if in bounds
   */
  def previous(i: Int): Option[To] =
    if (i > 0 && i <= history.length) Some(history(history.length - i)) else None

  /** Returns the most recently added value, if any */
  def last: Option[To] = history.lastOption

  /**
   * Effectfully modifies a previous element based on its position.
   * If the function returns None, the element is deleted.
   *
   * @param i the position to modify (1 = most recent)
   * @param f the effectful transformation function
   * @return a new Cursor with the updated or removed value
   */
  def modifyPrevious(i: Int)(f: To => Task[Option[To]]): Task[Cursor[From, To]] = Task.defer {
    val index = history.length - i
    if (index >= 0 && index < history.length) {
      f(history(index)).map {
        case Some(updated) => copy(history.updated(index, updated))
        case None          => copy(history.patch(index, Nil, 1))
      }
    } else Task.pure(this)
  }

  /**
   * Purely modifies a previous element.
   * No effect and no option — simply updates the value if the index is valid.
   *
   * @param i the position to modify (1 = most recent)
   * @param f the pure transformation function
   * @return a new Cursor with the updated value
   */
  def previousMap(i: Int)(f: To => To): Cursor[From, To] = {
    val index = history.length - i
    if (index >= 0 && index < history.length)
      copy(history.updated(index, f(history(index))))
    else this
  }

  /**
   * Applies an effectful transformation to all previously emitted elements,
   * processing them in reverse order (from newest to oldest) but preserving
   * the original order in the final result.
   *
   * @param f the effectful transformation to apply to each value
   * @return a new Cursor with transformed history
   */
  def mapHistory(f: To => Task[To]): Task[Cursor[From, To]] =
    rapid.Stream.emits(history.reverse)
      .evalMap(f)
      .toVector
      .map(v => Cursor(v.reverse))

  /**
   * Removes a previous value at the given offset from the end of the history.
   *
   * @param i the number of steps back (1 = most recent)
   * @return a new Cursor with the element removed, if it existed
   */
  def deletePrevious(i: Int): Task[Cursor[From, To]] =
    modifyPrevious(i)(_ => Task.pure(None))

  /**
   * Appends a new element to the cursor history.
   * This is the primary way to "emit" output during materialized stream processing.
   */
  def add(t: To): Cursor[From, To] =
    copy(history = history :+ t)

  /**
   * Finds the most recent element in history that satisfies the predicate.
   *
   * @param p the matching predicate
   * @return the most recent matching element, if any
   */
  def findPrevious(p: To => Boolean): Option[To] =
    history.reverseIterator.find(p)

  /**
   * Returns a slice of the most recent N values.
   *
   * @param n the number of elements to retrieve
   * @return a vector of the N most recent entries, in order
   */
  def previousSlice(n: Int): Vector[To] =
    history.takeRight(n)

  /**
   * Removes all values that don't match the predicate.
   *
   * @param f the filter predicate
   * @return a new Cursor with filtered history
   */
  def filter(f: To => Boolean): Cursor[From, To] =
    copy(history.filter(f))

  /**
   * Removes all history from the cursor, producing an empty version.
   */
  def clear(): Task[Cursor[From, To]] =
    Task.pure(copy(Vector.empty))
}