package rapid

import rapid.fiber.SynchronousFiber

import java.util.concurrent.{Executors, ScheduledExecutorService, ThreadFactory, TimeUnit}
import scala.concurrent.ExecutionContext

object Platform extends RapidPlatform {
  override def executionContext: ExecutionContext =
    rapid.fiber.FixedThreadPoolFiber.executionContext
  override def supportsCancel: Boolean = false
  override def createFiber[Return](task: Task[Return]): Fiber[Return] =
    if (Task.Virtual) new rapid.fiber.VirtualThreadFiber(task)
    else new rapid.fiber.FixedThreadPoolFiber(task)
  override def delay(millis: Long): Unit = Thread.sleep(millis)
  override def runAsync(task: Task[_]): Unit =
    rapid.fiber.FixedThreadPoolFiber.execute(() => { createFiber(task); () })

  override def yieldNow(): Unit = Thread.`yield`()

  override def defaultRandom: rapid.Forge[Int, Int] =
    rapid.Forge(max => rapid.Task(java.util.concurrent.ThreadLocalRandom.current().nextInt(max)))

  override def compileParallelStream[T, R](stream: ParallelStream[T, R], handle: R => Unit, complete: Int => Unit, onError: Throwable => Unit): Unit =
    ParallelStreamProcessor(stream, handle, complete, onError)

  override def schedule(thunk: () => Unit): Unit =
    executionContext.execute(() => thunk())

  private lazy val scheduledExecutor: ScheduledExecutorService = Executors.newScheduledThreadPool(
    Math.max(Runtime.getRuntime.availableProcessors(), 2),
    new ThreadFactory {
      override def newThread(r: Runnable): Thread = {
        val t = new Thread(r, "rapid-scheduler")
        t.setDaemon(true)
        t
      }
    }
  )

  override def scheduleDelay(millis: Long)(thunk: () => Unit): Unit =
    if (millis <= 0) schedule(thunk)
    else scheduledExecutor.schedule(new Runnable { def run(): Unit = thunk() }, millis, TimeUnit.MILLISECONDS)

  override def awaitFiber[R](fiber: Fiber[R]): R = fiber match {
    case sf: SynchronousFiber[R @unchecked] => sf.awaitBlocking()
    case other => other.sync()
  }
}
