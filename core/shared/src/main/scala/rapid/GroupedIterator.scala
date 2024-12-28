package rapid

/**
 * Convenience Iterator that groups sorted elements together based on the grouper function.
 *
 * Note: this is only useful is the underlying iterator is properly sorted by G.
 */
case class GroupedIterator[T, G](i: Iterator[T], grouper: T => G) extends Iterator[Grouped[G, T]] {
  private var current: Option[(G, T)] = if (i.hasNext) {
    val t = i.next()
    Some(grouper(t), t)
  } else {
    None
  }

  override def hasNext: Boolean = current.isDefined

  override def next(): Grouped[G, T] = current match {
    case Some((group, value)) =>
      current = None
      Grouped(group, value :: i.takeWhile { t =>
        val g = grouper(t)
        if (g != group) {
          current = Some((g, t))
          false
        } else {
          true
        }
      }.toList)
    case None => throw new NoSuchElementException("next on empty iterator")
  }
}