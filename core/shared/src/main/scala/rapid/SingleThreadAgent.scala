package rapid

import java.util.concurrent._
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.concurrent.duration.{DurationInt, FiniteDuration}

final class SingleThreadAgent[Resource] private (name: String, create: Task[Resource]) {
  private val closed       = new AtomicBoolean(false)
  private val ownerThread  = new AtomicReference[Thread](null)

  // Use a platform thread for true OS-thread affinity (recommended for JNI)
  private val exec: ExecutorService = Executors.newSingleThreadExecutor(new ThreadFactory {
    override def newThread(r: Runnable): Thread = {
      val t = new Thread(r, s"$name-sta") // UNSTARTED; executor will start it
      t.setDaemon(true)
      ownerThread.compareAndSet(null, t)
      t
    }
  })

  // Lazily create the resource on the agent thread at first use
  private lazy val resource: Resource = create.sync()

  private def isOwner: Boolean = Thread.currentThread() eq ownerThread.get()

  /** Run f(resource) on the agent thread and return a Task with the result. */
  def apply[Return](f: Resource => Return): Task[Return] = {
    if (closed.get()) return Task.error(new RejectedExecutionException(s"$name is closed"))
    // Reentrancy fast-path: already on agent thread → run inline
    if (isOwner) return Task(f(resource))

    val p = Task.completable[Return]
    try {
      exec.execute(() => {
        try p.success(f(resource))
        catch { case t: Throwable => p.failure(t) }
      })
    } catch {
      case t: RejectedExecutionException => p.failure(t)
    }
    p
  }

  /** Gracefully stop the agent and close the resource if applicable. */
  def dispose(timeout: FiniteDuration = 5.seconds): Task[Unit] = Task {
    if (closed.compareAndSet(false, true)) {
      exec.shutdown()
      val terminated = exec.awaitTermination(timeout.toMillis, TimeUnit.MILLISECONDS)
      if (!terminated) {
        exec.shutdownNow()
        exec.awaitTermination(1, TimeUnit.SECONDS)
      }
      resource match {
        case ac: AutoCloseable => try ac.close() catch { case _: Throwable => () }
        case _ =>
      }
    }
  }
}

object SingleThreadAgent {
  def apply[Resource](name: String)(create: Task[Resource]): SingleThreadAgent[Resource] =
    new SingleThreadAgent(name, create)
}