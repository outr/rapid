package rapid

import java.util.concurrent._
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.concurrent.duration.{DurationInt, FiniteDuration}

final class SingleThreadAgent[Resource] private (name: String,
                                                 create: Task[Resource],
                                                 preferVirtualThread: Boolean) {
  private val closed       = new AtomicBoolean(false)
  private val ownerThread  = new AtomicReference[Thread](null)

  private val exec: ExecutorService = Executors.newSingleThreadExecutor(new ThreadFactory {
    override def newThread(r: Runnable): Thread = {
      val t = if (preferVirtualThread) {
        Thread.ofVirtual().name(s"$name-sta").factory().newThread(r)
      } else {
        val pt = new Thread(r, s"$name-sta")
        pt.setDaemon(true)
        pt
      }
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
  /**
   * Creates a SingleThreadAgent backed by a **platform thread**.
   *
   * Prefer this for JNI / thread-confined native resources (ex: RocksDB iterators).
   */
  def apply[Resource](name: String)(create: Task[Resource]): SingleThreadAgent[Resource] =
    new SingleThreadAgent(name, create, preferVirtualThread = false)

  /**
   * Creates a SingleThreadAgent backed by a **virtual thread**.
   *
   * Note: Virtual threads are NOT guaranteed OS-thread-affine, so don't use this for JNI thread confinement.
   */
  def virtual[Resource](name: String)(create: Task[Resource]): SingleThreadAgent[Resource] =
    new SingleThreadAgent(name, create, preferVirtualThread = true)
}
