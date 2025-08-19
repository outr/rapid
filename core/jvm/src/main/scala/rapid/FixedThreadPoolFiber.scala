package rapid
import java.util.concurrent._
import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.mutable.ArrayDeque
import rapid.scheduler.CancelToken
import rapid.task._
import rapid.util.OneShot
import rapid.runtime.RapidRuntime

object FixedThreadPoolFiber {
  lazy val executor: ExecutorService = Executors.newFixedThreadPool(
    math.max(4, Runtime.getRuntime.availableProcessors()),
    (r: Runnable) => {
      val t = new Thread(r)
      t.setDaemon(true)
      t.setName(s"rapid-ft-${t.getId}")
      t
    }
  )

  def fireAndForget[A](task: Task[A]): Unit = { new FixedThreadPoolFiber[A](task); () }
}

final class FixedThreadPoolFiber[A](initial: Task[A]) extends Fiber[A] with rapid.runtime.TimerWakeable {
  import FixedThreadPoolFiber._

  // P1-4: OneShot instead of CompletableFuture
  private val done = new OneShot[A]()
  @volatile private var canceled = false
  @volatile private var cancelTok: CancelToken = _
  

  override def sync(): A = done.get() // OK to block *outside* the pool

  // P1-1: Inline first activation if already on our pool thread to avoid one hop
  private def onPoolThread: Boolean =
    Thread.currentThread.getName.startsWith("rapid-ft-")

  private val loop = new RunLoop[A](initial)
  if (onPoolThread) loop.start()
  else executor.execute(() => loop.start())

  override def cancel: Task[Boolean] = Task {
    canceled = true
    val t = cancelTok; if (t != null) t.cancel()
    true
  }

  /** Called by the timer's drain task on a pool thread to resume this fiber with Unit + optional cont. */
  override def __externalResumeFromTimer(cont: AnyRef): Unit = {
    if (canceled) return
    // 1) enqueue the continuation first
    if (cont != null) loop.conts.prepend(cont.asInstanceOf[Any => Task[Any]])
    // 2) try to become the runner once
    if (loop.running.compareAndSet(false, true)) {
      loop.cur = PureTask(()).asInstanceOf[Task[Any]]
      loop.runLoopExternal()
    }
    // else: the active loop will consume the newly enqueued conts
  }

  /** Minimal, non-blocking interpreter (P1: fewer hops, less churn + P3: direct timer integration). */
  private final class RunLoop[B](initial: Task[B]) {
    private[FixedThreadPoolFiber] val conts = new ArrayDeque[Any => Task[Any]]()
    private[FixedThreadPoolFiber] val running = new AtomicBoolean(false)
    private[FixedThreadPoolFiber] var cur: Task[Any] = initial.asInstanceOf[Task[Any]]

    def start(): Unit = runLoop()

    @inline private def resumeWith(v: Any): Unit = { cur = PureTask(v).asInstanceOf[Task[Any]]; runLoop() }
    @inline private def completeWith(v: Any): Unit = { done.complete(v.asInstanceOf[A]); () }
    @inline private def failWith(e: Throwable): Unit = { done.fail(e); () }

    // NEW: the body with no CAS
    @noinline private def runLoopBody(): Unit = {
      var loop = true
      while (loop && !canceled && !done.isDone) {
        (cur: @unchecked) match {
          case p: PureTask[_] =>
            val v = p.value
            if (conts.isEmpty) { completeWith(v); loop = false }
            else cur = conts.removeHead()(v)

          case _: UnitTask =>
            val v = ()
            if (conts.isEmpty) { completeWith(v); loop = false }
            else cur = conts.removeHead()(v)

          case s: SingleTask[_] =>
            // must not block
            cur = PureTask(s.f().asInstanceOf[Any])

          case sleep: SleepTask =>
            // For tests, respect the platform's timer (ScheduledExecutorTimer, etc.)
            // For production/bench, keep the fast path via RapidRuntime.timer2
            if (java.lang.Boolean.getBoolean("rapid.tests.usePlatformTimer")) {
              // Defer to the public API (Sleep.sleep) which uses p.timer
              cur = Platform.sleep(sleep.duration).asInstanceOf[Task[Any]]
            } else {
              // P3 fast path: direct registration on the wheel timer
              val deadline = RapidRuntime.timer2.nowNanos() + sleep.duration.toNanos
              val k: AnyRef = if (conts.isEmpty) null else conts.removeHead()
              cancelTok = RapidRuntime.timer2.registerSleep(deadline, FixedThreadPoolFiber.this, k)
              loop = false // park fiber; worker returns to pool
            }

          case fm: FlatMapTask[_, _] =>
            // P1-5: reuse forge; no extra wrapper allocation
            val f = fm.forge.asInstanceOf[Forge[Any, Any]]
            conts.prepend((a: Any) => f(a).asInstanceOf[Task[Any]])
            cur = fm.source.asInstanceOf[Task[Any]]

          case err: ErrorTask[_] =>
            failWith(err.throwable); loop = false

          // Bridge CompletableTask to async w/o blocking:
          case c: CompletableTask[_] =>
            cur = Task.async[Any] { cb =>
              c.onComplete {
                case Right(v) => cb(Right(v))
                case Left(e)  => cb(Left(e))
              }
              new CancelToken { def cancel(): Unit = () }
            }

          case async: AsyncTask[_] =>
            // SUSPEND: free worker. Resume through the same enqueue-then-CAS path as the timer.
            val fired = new java.util.concurrent.atomic.AtomicBoolean(false)
            cancelTok = async.register {
              case Right(v) =>
                if (!canceled && fired.compareAndSet(false, true)) {
                  // cont ignores input and resumes with v
                  val cont = ((_: Any) => PureTask(v).asInstanceOf[Task[Any]]): AnyRef
                  FixedThreadPoolFiber.this.__externalResumeFromTimer(cont)
                }
              case Left(e)  =>
                if (!canceled && fired.compareAndSet(false, true)) {
                  // cont ignores input and raises e
                  val cont = ((_: Any) => ErrorTask[Any](e).asInstanceOf[Task[Any]]): AnyRef
                  FixedThreadPoolFiber.this.__externalResumeFromTimer(cont)
                }
            }
            loop = false // yield

          case other =>
            failWith(new UnsupportedOperationException(s"Unsupported Task node: $other"))
            loop = false
        }
      }
    }

    // NORMAL ENTRY: acquires guard, runs body, releases guard
    private def runLoop(): Unit = {
      if (!running.compareAndSet(false, true)) return // no re-entrancy
      try {
        runLoopBody()
      } catch {
        case t: Throwable => failWith(t)
      } finally {
        // P1-2: No unconditional tail reschedule. We only reschedule
        // when new work arrives via async callback (which will either
        // own the guard and run inline, or schedule exactly once).
        running.set(false)
      }
    }

    // EXTERNAL ENTRY: assumes guard is already held
    private[FixedThreadPoolFiber] def runLoopExternal(): Unit = {
      // precondition: running == true (acquired by caller)
      try {
        runLoopBody()
      } catch {
        case t: Throwable => failWith(t)
      } finally {
        running.set(false)
      }
    }
  }
}