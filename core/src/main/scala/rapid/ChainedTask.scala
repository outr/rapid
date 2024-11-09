package rapid

case class ChainedTask[Return](list: List[Any => Task[Any]]) extends Task[Return] {
  override protected def f: () => Return = () => list.reverse.foldLeft((): Any)((value, f) => f(value).sync()).asInstanceOf[Return]

  override def flatMap[T](f: Return => Task[T]): Task[T] = copy(f.asInstanceOf[Any => Task[Any]] :: list)
}
