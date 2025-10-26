package rapid.fiber

import rapid.task.{Completable, FlatMap, Pure, Sleep, Suspend, Taskable, UnitTask}
import rapid.trace.Trace
import rapid.{Fiber, Task}

import java.util
import java.util.concurrent.{CountDownLatch, Executors, LinkedBlockingQueue, ScheduledExecutorService, ThreadFactory, ThreadPoolExecutor, TimeUnit}

case class FixedThreadPoolFiber[Return](task: Task[Return]) extends Fiber[Return] {
  // Minimal completion mechanics with lazy latch to avoid per-fiber allocation when not awaited
  @volatile private var completed: Boolean = false
  @volatile private var failure: Throwable = _
  private var result: Any = _
  private var done: CountDownLatch = _

  private def completeSuccess(v: Any): Unit = {
    result = v
    completed = true
    val l = done
    if (l ne null) l.countDown()
  }
  private def completeFailure(t: Throwable): Unit = {
    failure = t
    completed = true
    val l = done
    if (l ne null) l.countDown()
  }

  private final class Interpreter(root: Task[Return]) extends Runnable {
    private val stack = new util.ArrayDeque[Any]()
    private var previous: Any = ()
    // tracing support (mirrors SynchronousFiber)
    private val TraceBufSize = 256
    private val tracing = Trace.Enabled
    private val traceBuf: Array[Trace] = if (tracing) new Array[Trace](TraceBufSize) else null
    private var traceIdx = 0
    private var lastTrace: Trace = Trace.empty

    @inline private def record(tr: Trace): Unit = if (tracing && tr != Trace.empty) {
      traceBuf(traceIdx & (TraceBufSize - 1)) = tr
      traceIdx += 1
    }
    // Reuse a single resume runnable to avoid per-sleep/callback allocation
    private val resumeRunnable: Runnable = new Runnable {
      override def run(): Unit = FixedThreadPoolFiber.executor.execute(Interpreter.this)
    }

    stack.push(root)

    private def stepLoop(scheduleOnYield: Boolean): Unit = {
      try {
        var continue = true
        while (continue && !stack.isEmpty) {
          def handle(value: Any): Unit = value match {
            case t: Task[_] =>
              t match {
                case _: UnitTask => previous = ()
                case t: Taskable[_] => handle(t.toTask)
                case Pure(r) => previous = r
                case Suspend(f, tr) =>
                  if (tracing) {
                    lastTrace = tr
                    record(tr)
                  }
                  previous = f()
                case Sleep(duration, tr) =>
                  if (tracing) {
                    lastTrace = tr
                    record(tr)
                  }
                  continue = false
                  val ms = duration.toMillis
                  if (ms >= 1000L) {
                    val deadline = System.currentTimeMillis() + ms
                    SleepScheduler.schedule(deadline, resumeRunnable)
                  } else {
                    FixedThreadPoolFiber.scheduler.schedule(resumeRunnable, ms, TimeUnit.MILLISECONDS)
                  }
                  previous = ()
                case c: Completable[_] =>
                  if (tracing) {
                    lastTrace = c.trace
                    record(c.trace)
                  }
                  continue = false
                  c.onComplete {
                    case scala.util.Success(v) =>
                      previous = v
                      resumeRunnable.run()
                    case scala.util.Failure(t) => completeFailure(t)
                  }
                case FlatMap(input, f, tr) =>
                  // When tracing, push (f, tr) so we can record on application
                  if (tracing) stack.push((f, tr)) else stack.push(f)
                  stack.push(input)
              }
            case f: Function1[_, _] =>
              val next = f.asInstanceOf[Any => Task[Any]](previous)
              stack.push(next)
            case (f: Function1[_, _], tr: Trace) =>
              if (tracing) {
                lastTrace = tr
                record(tr)
              }
              val next = f.asInstanceOf[Any => Task[Any]](previous)
              stack.push(next)
          }
          handle(stack.pop())
        }
        if (continue && stack.isEmpty) completeSuccess(previous)
      } catch {
        case t: Throwable =>
          if (tracing) {
            val n = Math.min(traceIdx, TraceBufSize)
            val frames = List.tabulate(n)(i => traceBuf((traceIdx - 1 - i) & (TraceBufSize - 1))).filter(_ != Trace.empty).distinct
            completeFailure(Trace.update(t, frames))
          } else {
            completeFailure(t)
          }
      }
    }

    // Prime inline without scheduling to executor unless we yielded
    def prime(): Unit = stepLoop(scheduleOnYield = false)

    override def run(): Unit = stepLoop(scheduleOnYield = true)
  }

  // Always schedule on the executor to avoid deadlocks/starvation in parallel operations
  FixedThreadPoolFiber.executor.execute(new Interpreter(task))

  override def sync(): Return = {
    if (!completed) {
      var l = done
      if (l eq null) this.synchronized {
        if (done eq null) done = new CountDownLatch(1)
        l = done
      }
      if (!completed) l.await()
    }
    val f = failure
    if (f ne null) throw f
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

  // ThreadPoolExecutor with a large bounded queue performs well for many tiny tasks
  private[fiber] lazy val executor: ThreadPoolExecutor = new ThreadPoolExecutor(
    Math.max(Runtime.getRuntime.availableProcessors() * 2, 8),
    Math.max(Runtime.getRuntime.availableProcessors() * 2, 8),
    60L,
    TimeUnit.SECONDS,
    new LinkedBlockingQueue[Runnable](32768),
    threadFactory,
    new ThreadPoolExecutor.CallerRunsPolicy()
  )

  private lazy val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(
    Math.max(Runtime.getRuntime.availableProcessors(), 4),
    new ThreadFactory {
      override def newThread(r: Runnable): Thread = {
        val t = new Thread(r, "rapid-v2-scheduler")
        t.setDaemon(true)
        t
      }
    }
  )

  /** Public entry to schedule a runnable onto the fiber executor. */
  def execute(runnable: Runnable): Unit = executor.execute(runnable)
}