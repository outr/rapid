package rapid

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.{CancellationException, Executors, Future, ThreadFactory, TimeUnit}
import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Try}

class FixedThreadPoolFiber[Return](val task: Task[Return]) extends Blockable[Return] with Fiber[Return] {
  @volatile private var result: Try[Return] = _
  @volatile private var cancelled = false

  private val future = FixedThreadPoolFiber.create(task)

  override def sync(): Return = {
    result = Try(future.get())
    if (result == null && cancelled) {
      result = Failure(new CancellationException())
    }
    result.get
  }

  override def cancel(): Task[Boolean] = Task {
    if (!cancelled) {
      cancelled = true
      future.cancel(true)
      true
    } else {
      false
    }
  }

  override def await(duration: FiniteDuration): Option[Return] = if (Try(future.get(duration.toMillis, TimeUnit.MILLISECONDS)).isSuccess) {
    Option(result).flatMap(_.toOption)
  } else {
    None
  }
}

object FixedThreadPoolFiber {
  private lazy val threadFactory = new ThreadFactory {
    override def newThread(r: Runnable): Thread = {
      val thread = new Thread(r)
      thread.setName(s"rapid-ft-${FixedThreadPoolFiber.counter.incrementAndGet()}")
      thread.setDaemon(true)
      thread
    }
  }
  private lazy val executor = Executors.newFixedThreadPool(
    math.max(Runtime.getRuntime.availableProcessors(), 4),
    threadFactory
  )
  private val counter = new AtomicLong(0L)

  private def create[Return](task: Task[Return]): Future[Return] = executor.submit(() => task.sync())

  def fireAndForget[Return](task: Task[Return]): Unit = create(task)
}