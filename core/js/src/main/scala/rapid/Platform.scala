package rapid

import rapid.fiber.SynchronousFiber

import scala.concurrent.ExecutionContext
import scala.scalajs.js

object Platform extends RapidPlatform {
  override def executionContext: ExecutionContext = org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global

  override def supportsCancel: Boolean = false

  override def createFiber[Return](task: Task[Return]): Fiber[Return] =
    new SynchronousFiber[Return](task)

  override def isVirtualThread: Boolean = false
  override def delay(millis: Long): Unit = ()

  override def runAsync(task: Task[_]): Unit =
    schedule(() => { createFiber(task); () })

  override def yieldNow(): Unit = ()

  override def defaultRandom: rapid.Forge[Int, Int] =
    rapid.Forge(max => rapid.Task(js.Math.floor(js.Math.random() * max).toInt))

  override def compileParallelStream[T, R](stream: ParallelStream[T, R], handle: R => Unit, complete: Int => Unit, onError: Throwable => Unit): Unit = {
    try {
      val pull = Stream.task(stream.stream).sync()
      var count = 0
      val stack = scala.collection.mutable.ArrayDeque[Pull[T]]()
      var current: Pull[T] = pull
      var done = false
      while (!done) {
        current.pull.sync() match {
          case Step.Emit(t) =>
            stream.forge(t).sync() match {
              case Some(r) => handle(r); count += 1
              case None => ()
            }
          case Step.Skip => ()
          case Step.Stop => if (stack.isEmpty) done = true else current = stack.removeLast()
          case Step.Concat(inner) => stack.append(current); current = inner
        }
      }
      try pull.close.sync() catch { case _: Throwable => () }
      complete(count)
    } catch {
      case t: Throwable => onError(t)
    }
  }

  override def schedule(thunk: () => Unit): Unit =
    js.timers.setTimeout(0)(thunk())

  override def scheduleDelay(millis: Long)(thunk: () => Unit): Unit =
    js.timers.setTimeout(millis.toDouble)(thunk())

  override def awaitFiber[R](fiber: Fiber[R]): R = fiber match {
    case sf: SynchronousFiber[R @unchecked] =>
      sf.resultOpt match {
        case Some(scala.util.Success(r)) => r
        case Some(scala.util.Failure(t)) => throw t
        case None => throw new UnsupportedOperationException(
          "Cannot synchronously await an incomplete fiber on Scala.js. Use .toFuture or .runAsync instead."
        )
      }
    case _ => throw new UnsupportedOperationException(
      "Cannot synchronously await a fiber on Scala.js. Use .toFuture or .runAsync instead."
    )
  }
}
