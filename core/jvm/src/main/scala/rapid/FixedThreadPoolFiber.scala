package rapid

import java.util.concurrent.{Executors, Future => JFuture, ScheduledExecutorService, ThreadFactory, TimeUnit}
import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.duration.FiniteDuration
import scala.util.Try

class FixedThreadPoolFiber[Return](val task: Task[Return]) extends Blockable[Return] with Fiber[Return] {
  @volatile private var cancelled = false

  private val future: JFuture[Return] = FixedThreadPoolFiber.executor.submit(() => task.sync())

  override def sync(): Return = try {
    future.get()
  } catch {
    case e: java.util.concurrent.ExecutionException => throw e.getCause
    case e: Throwable => throw e
  }

  override def cancel: Task[Boolean] = Task {
    if (!cancelled) {
      cancelled = true
      future.cancel(true)
      true
    } else {
      false
    }
  }

  override def await(duration: FiniteDuration): Option[Return] = try {
    val result = future.get(duration.toMillis, TimeUnit.MILLISECONDS)
    Some(result)
  } catch {
    case _: java.util.concurrent.TimeoutException => None
    case e: java.util.concurrent.ExecutionException => throw e.getCause
    case e: Throwable => throw e
  }

  // ✅ Correctly implemented TaskLike
  override def start: TaskLike[Return] = new TaskLike[Return] {
    def sync(): Return = FixedThreadPoolFiber.this.sync()
    def await(): Return = FixedThreadPoolFiber.this.await()
    def start: TaskLike[Return] = this
  }
}

object FixedThreadPoolFiber {
  private val counter = new AtomicLong(0L)

  private lazy val threadFactory = new ThreadFactory {
    override def newThread(r: Runnable): Thread = {
      val thread = new Thread(r)
      thread.setName(s"rapid-ft-${counter.incrementAndGet()}")
      thread.setDaemon(true)
      thread
    }
  }

  lazy val executor = Executors.newFixedThreadPool(
    math.max(Runtime.getRuntime.availableProcessors(), 4),
    threadFactory
  )

  lazy val scheduledExecutor: ScheduledExecutorService = Executors.newScheduledThreadPool(
    math.max(Runtime.getRuntime.availableProcessors() / 2, 2),
    new ThreadFactory {
      override def newThread(r: Runnable): Thread = {
        val thread = new Thread(r)
        thread.setName(s"rapid-scheduler-${counter.incrementAndGet()}")
        thread.setDaemon(true)
        thread
      }
    }
  )

  def fireAndForget[Return](task: Task[Return]): Unit = executor.submit(() => Try(task.sync()))

  def shutdown(): Unit = {
    executor.shutdown()
    scheduledExecutor.shutdown()
  }
}
