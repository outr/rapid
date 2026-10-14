package rapid

import rapid.fiber.SynchronousFiber

import java.util.concurrent.{Executors, ScheduledExecutorService, ThreadFactory, TimeUnit}
import scala.concurrent.ExecutionContext

object Platform extends RapidPlatform {
  /** Whether this runtime has virtual threads at all.
    *
    * `Task.Virtual` is a switch, not an answer: it is true by default, and on a runtime without virtual threads
    * that sent every fiber to a `Thread.ofVirtual` that is not there. Android has no virtual threads at any
    * version, and neither does a JVM before 21. Asked once, here, and every use of them is gated on it. */
  private val hasVirtual: Boolean =
    try {
      classOf[Thread].getMethod("ofVirtual")
      true
    } catch {
      case _: Throwable => false
    }

  override def executionContext: ExecutionContext =
    rapid.fiber.FixedThreadPoolFiber.executionContext
  override def supportsCancel: Boolean = false
  override def createFiber[Return](task: Task[Return]): Fiber[Return] =
    if (Task.Virtual && hasVirtual) new rapid.fiber.VirtualThreadFiber(task)
    else new rapid.fiber.FixedThreadPoolFiber(task)
  override def isVirtualThread: Boolean = hasVirtual && Thread.currentThread().isVirtual
  override def delay(millis: Long): Unit = Thread.sleep(millis)
  override def runAsync(task: Task[_]): Unit =
    if (Task.Virtual && hasVirtual) {
      Thread.ofVirtual().start(() => { new SynchronousFiber(task); () })
    } else {
      rapid.fiber.FixedThreadPoolFiber.execute(() => { new SynchronousFiber(task); () })
    }

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
