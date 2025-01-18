package rapid

import scala.collection.mutable.ListBuffer

class GroupingIterator[A](iter: Iterator[A], isSeparator: A => Boolean) extends Iterator[List[A]] {
  private val buffer = ListBuffer.empty[A]

  override def hasNext: Boolean = buffer.nonEmpty || iter.hasNext

  override def next(): List[A] = {
    if (!hasNext) throw new NoSuchElementException("next on empty iterator")

    while (iter.hasNext) {
      val elem = iter.next()
      if (isSeparator(elem)) {
        val group = buffer.toList
        buffer.clear()
        return group
      } else {
        buffer += elem
      }
    }

    // Return remaining buffer if iterator is exhausted
    val remaining = buffer.toList
    buffer.clear()
    remaining
  }
}

object GroupingIterator {
  def apply[A](iter: Iterator[A])(isSeparator: A => Boolean): GroupingIterator[A] =
    new GroupingIterator(iter, isSeparator)
}