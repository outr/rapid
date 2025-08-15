package rapid

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.{Executors, Future, ScheduledExecutorService, ThreadFactory, TimeUnit}
import scala.concurrent.duration.FiniteDuration
import scala.util.Try

class FixedThreadPoolFiber[Return](val task: Task[Return]) extends Blockable[Return] with Fiber[Return] {
  @volatile private var cancelled = false

  private val future = FixedThreadPoolFiber.create(task)

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
}

object FixedThreadPoolFiber {
  private lazy val threadFactory = new ThreadFactory {
    override def newThread(r: Runnable): Thread = {
      val thread = new Thread(r)
      thread.setName(s"rapid-ft-${counter.incrementAndGet()}")
      thread.setDaemon(true)
      thread
    }
  }
  
  private lazy val executor = Executors.newFixedThreadPool(
    math.max(Runtime.getRuntime.availableProcessors(), 4),
    threadFactory
  )
  
  private lazy val scheduledExecutor: ScheduledExecutorService = Executors.newScheduledThreadPool(
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
  
  private val counter = new AtomicLong(0L)

  private def create[Return](task: Task[Return]): Future[Return] = executor.submit(() => task.sync())

  def fireAndForget[Return](task: Task[Return]): Unit = executor.submit(() => Try(task.sync()))
  
  // Shutdown method for cleanup
  def shutdown(): Unit = {
    executor.shutdown()
    scheduledExecutor.shutdown()
  }
}