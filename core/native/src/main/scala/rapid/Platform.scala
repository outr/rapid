package rapid

import rapid.fiber.SynchronousFiber

import scala.concurrent.ExecutionContext

object Platform extends RapidPlatform {
  override def executionContext: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global

  override def supportsCancel: Boolean = false

  override def createFiber[Return](task: Task[Return]): Fiber[Return] =
    new rapid.fiber.FutureBlockableFiber[Return](task)(executionContext)

  override def delay(millis: Long): Unit = Thread.sleep(millis)

  override def runAsync(task: Task[_]): Unit =
    executionContext.execute(() => { createFiber(task); () })

  override def yieldNow(): Unit = Thread.`yield`()

  override def defaultRandom: rapid.Forge[Int, Int] =
    rapid.Forge(max => rapid.Task(scala.util.Random.nextInt(max)))

  override def compileParallelStream[T, R](stream: ParallelStream[T, R], handle: R => Unit, complete: Int => Unit, onError: Throwable => Unit): Unit = {
    try {
      val pull = Stream.task(stream.stream).sync()
      var count = 0
      val stack = new java.util.ArrayDeque[Pull[T]]()
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
          case Step.Stop => if (stack.isEmpty) done = true else current = stack.pop()
          case Step.Concat(inner) => stack.push(current); current = inner
        }
      }
      try pull.close.sync() catch { case _: Throwable => () }
      complete(count)
    } catch {
      case t: Throwable => onError(t)
    }
  }

  override def schedule(thunk: () => Unit): Unit =
    executionContext.execute(() => thunk())

  override def scheduleDelay(millis: Long)(thunk: () => Unit): Unit = {
    if (millis <= 0) schedule(thunk)
    else {
      val thread = new Thread(() => {
        Thread.sleep(millis)
        thunk()
      })
      thread.setDaemon(true)
      thread.start()
    }
  }

  override def awaitFiber[R](fiber: Fiber[R]): R = fiber match {
    case sf: SynchronousFiber[R @unchecked] => sf.awaitBlocking()
    case other => other.sync()
  }
}
