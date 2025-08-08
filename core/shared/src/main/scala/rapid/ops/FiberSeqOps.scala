package rapid.ops

import rapid.{Fiber, Task}

import scala.collection.BuildFrom

final case class FiberSeqOps[Return, C[_]](private val fibers: C[Fiber[Return]]) extends AnyVal {
  def joinAll(implicit it: C[Fiber[Return]] => Iterable[Fiber[Return]],
              bf: BuildFrom[C[Fiber[Return]], Return, C[Return]]): Task[C[Return]] = Task {
    val b = bf.newBuilder(fibers)
    it(fibers).foreach(f => b += f.sync())
    b.result()
  }
}