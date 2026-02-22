package rapid.ops

import rapid.{Fiber, Task}

import scala.collection.BuildFrom

final case class FiberSeqOps[Return, C[_]](private val fibers: C[Fiber[Return]]) extends AnyVal {
  def joinAll(implicit it: C[Fiber[Return]] => Iterable[Fiber[Return]],
              bf: BuildFrom[C[Fiber[Return]], Return, C[Return]]): Task[C[Return]] = {
    val fiberList = it(fibers).toList
    fiberList.foldLeft(Task.pure(bf.newBuilder(fibers))) { (accTask, fiber) =>
      accTask.flatMap(builder => fiber.join.map(v => builder += v))
    }.map(_.result())
  }
}
