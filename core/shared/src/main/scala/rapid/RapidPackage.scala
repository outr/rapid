package rapid

import rapid.ops.{ByteStreamOps, CharStreamOps, FiberSeqOps, OptionParallelStreamOps, OptionStreamOps, TaskSeqOps, TaskTaskOps}

import scala.language.implicitConversions

trait RapidPackage {
  implicit def taskSeq2Ops[Return, C[_]](tasks: C[Task[Return]]): TaskSeqOps[Return, C] = TaskSeqOps(tasks)
  implicit def fiberSeq2Ops[Return, C[_]](fibers: C[Fiber[Return]]): FiberSeqOps[Return, C] = FiberSeqOps(fibers)
  implicit def optionStream[Return](stream: Stream[Option[Return]]): OptionStreamOps[Return] = OptionStreamOps(stream)
  implicit def optionParallelStream[T, Return](stream: ParallelStream[T, Option[Return]]): OptionParallelStreamOps[T, Return] = OptionParallelStreamOps(stream)
  implicit def taskTaskOps[Return](task: Task[Task[Return]]): TaskTaskOps[Return] = TaskTaskOps(task)
  implicit def byteStream(stream: Stream[Byte]): ByteStreamOps = ByteStreamOps(stream)
  implicit def charStream(stream: Stream[Char]): CharStreamOps = CharStreamOps(stream)

  implicit final class FiberOps[+A](private val fiber: Fiber[A]) {
    def map[B](f: A => B): Task[B] = Task(f(fiber.sync()))
    def flatMap[B](f: A => Task[B]): Task[B] = f(fiber.sync())
    def cancel: Task[Boolean] = Task.pure(false)
  }
}
