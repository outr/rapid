package rapid.v2

import java.util
import java.util.concurrent.{CountDownLatch, Executors, LinkedBlockingQueue, ScheduledExecutorService, ThreadFactory, ThreadPoolExecutor, TimeUnit}

case class FixedThreadPoolFiber[Return](task: Task[Return]) extends Fiber[Return] {
  // Minimal completion mechanics to avoid Future/Try allocations
  private[this] val done = new CountDownLatch(1)
  private[this] var result: Any = _
  private[this] var failure: Throwable = null

  private[this] def completeSuccess(v: Any): Unit = { result = v; done.countDown() }
  private[this] def completeFailure(t: Throwable): Unit = { failure = t; done.countDown() }

  private[this] final class Interpreter(root: Task[Return]) extends Runnable {
    private[this] val stack = new util.ArrayDeque[Any]()
    private[this] var previous: Any = ()
    // Reuse a single resume runnable to avoid per-sleep/callback allocation
    private[this] val resumeRunnable: Runnable = new Runnable {
      override def run(): Unit = FixedThreadPoolFiber.executor.execute(Interpreter.this)
    }

    stack.push(root)

    private[this] def stepLoop(scheduleOnYield: Boolean): Unit = {
      try {
        var continue = true
        while (continue && !stack.isEmpty) {
          stack.pop() match {
            case t: Task[_] =>
              t match {
                case _: UnitTask => ()
                case Pure(r) => previous = r
                case Suspend(f, _) => previous = f()
                case Sleep(duration, _) =>
                  continue = false
                  val ms = duration.toMillis
                  if (ms >= 1000L) {
                    val deadline = System.currentTimeMillis() + ms
                    SleepScheduler.schedule(deadline, resumeRunnable)
                  } else {
                    FixedThreadPoolFiber.scheduler.schedule(resumeRunnable, ms, TimeUnit.MILLISECONDS)
                  }
                case c@Completable(_) =>
                  continue = false
                  c.onComplete {
                    case scala.util.Success(v) => previous = v; resumeRunnable.run()
                    case scala.util.Failure(t) => completeFailure(t)
                  }
                case FlatMap(input, f, _) =>
                  stack.push(f)
                  stack.push(input)
              }
            case f: Function1[_, _] =>
              val next = f.asInstanceOf[Any => Task[Any]](previous)
              stack.push(next)
            case (f: Function1[_, _], _) =>
              val next = f.asInstanceOf[Any => Task[Any]](previous)
              stack.push(next)
          }
        }
        if (continue && stack.isEmpty) completeSuccess(previous)
        else if (!continue && scheduleOnYield) FixedThreadPoolFiber.executor.execute(this)
      } catch {
        case t: Throwable => completeFailure(t)
      }
    }

    // Prime inline without scheduling to executor unless we yielded
    def prime(): Unit = stepLoop(scheduleOnYield = false)

    override def run(): Unit = stepLoop(scheduleOnYield = true)
  }

  // Kick off with a priming pass inline: most sleep-heavy tasks yield immediately without occupying the pool
  new Interpreter(task).prime()

  override def sync(): Return = {
    done.await()
    if (failure ne null) throw failure
    result.asInstanceOf[Return]
  }
}

object FixedThreadPoolFiber {
  private lazy val threadFactory = new ThreadFactory {
    override def newThread(r: Runnable): Thread = {
      val thread = new Thread(r, "rapid-v2-worker")
      thread.setDaemon(true)
      thread
    }
  }

  // ForkJoinPool reduces contention for many tiny tasks via per-worker deques
  private[v2] lazy val executor: ThreadPoolExecutor = new ThreadPoolExecutor(
    Math.max(Runtime.getRuntime.availableProcessors() * 2, 8),
    Math.max(Runtime.getRuntime.availableProcessors() * 2, 8),
    60L,
    TimeUnit.SECONDS,
    new LinkedBlockingQueue[Runnable](32768),
    threadFactory,
    new ThreadPoolExecutor.CallerRunsPolicy()
  )

  private[v2] lazy val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(
    Math.max(Runtime.getRuntime.availableProcessors(), 4),
    new ThreadFactory {
      override def newThread(r: Runnable): Thread = {
        val t = new Thread(r, "rapid-v2-scheduler")
        t.setDaemon(true)
        t
      }
    }
  )
}