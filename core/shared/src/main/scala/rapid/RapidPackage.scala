package rapid

import rapid.ops._   // brings in TaskSeqOps, FiberSeqOps, etc.
import scala.language.implicitConversions

/** Central place for syntax extensions in Rapid. */
trait RapidPackage {

  /** Enable `.tasks` / `.tasksPar` on any C[Task[A]] where C is IterableOnce. */
  implicit def taskSeq2Ops[Return, C[X] <: IterableOnce[X]](
    tasks: C[Task[Return]]
  ): TaskSeqOps[Return, C] =
    TaskSeqOps(tasks)

  /** Enable `.tasks` / `.tasksPar` on collections of Fiber. */
  implicit def fiberSeq2Ops[Return, C[X] <: IterableOnce[X]](
    fibers: C[Fiber[Return]]
  ): FiberSeqOps[Return, C] =
    FiberSeqOps(fibers)

  /** Add `.flattenOptions` or similar helpers on Stream[Option[A]]. */
  implicit def optionStream[Return](
    stream: Stream[Option[Return]]
  ): OptionStreamOps[Return] =
    OptionStreamOps(stream)

  /** Add helpers on ParallelStream[T, Option[A]]. */
  implicit def optionParallelStream[T, Return](
    stream: ParallelStream[T, Option[Return]]
  ): OptionParallelStreamOps[T, Return] =
    OptionParallelStreamOps(stream)

  /** Flatten nested Task[Task[A]]. */
  implicit def taskTaskOps[Return](
    task: Task[Task[Return]]
  ): TaskTaskOps[Return] =
    TaskTaskOps(task)

  /** Add ops for byte streams. */
  implicit def byteStream(
    stream: Stream[Byte]
  ): ByteStreamOps =
    ByteStreamOps(stream)

  /** Add ops for char streams. */
  implicit def charStream(
    stream: Stream[Char]
  ): CharStreamOps =
    CharStreamOps(stream)
}

/** Concrete object so users can `import rapid.RapidPackage._` */
object RapidPackage extends RapidPackage
