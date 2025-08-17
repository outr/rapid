package rapid.task

import rapid.{Task, Platform}
import scala.util.control.NonFatal

object TaskCombinators:

  extension [A](task: Task[A])

    /** Forks the task and returns the same task for chaining. */
    def start(): Task[A] =
      Platform.fork(task)
      task

    def attempt: Task[Either[Throwable, A]] =
      Task {
        try Right(task.sync())
        catch case NonFatal(t) => Left(t)
      }

    def handleError(f: Throwable => Task[A]): Task[A] =
      Task {
        try task.sync()
        catch case NonFatal(t) => f(t).sync()
      }

    def guarantee(finalizer: Task[Unit]): Task[A] =
      Task {
        try
          val result = task.sync()
          finalizer.sync()
          result
        catch {
          case t: Throwable =>
            try finalizer.sync()
            catch case _: Throwable => ()
            throw t
        }
      }

    def flatTap[B](f: A => Task[B]): Task[A] =
      task.flatMap(a => f(a).map(_ => a))
