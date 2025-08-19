package rapid

import rapid.task._
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.{Executors, CompletableFuture, ThreadFactory, ThreadPoolExecutor, TimeUnit}
import scala.concurrent.duration.FiniteDuration

class FixedThreadPoolFiber[Return](val task: Task[Return]) extends Blockable[Return] with Fiber[Return] {
  import FixedThreadPoolFiber._
  
  @volatile private var cancelled = false
  private val done = new CompletableFuture[Return]()
  private[this] val conts = new java.util.ArrayDeque[Any => Task[Any]]()
  @volatile private[this] var cur: Task[Any] = task.asInstanceOf[Task[Any]]
  @volatile private[this] var inLoop = false
  @volatile private[this] var pending = false
  
  // Start the fiber
  schedule()
  
  // Single scheduling gateway
  private[this] def schedule(): Unit = {
    if (inLoop) { pending = true; return }
    inLoop = true
    executor.execute(() => runLoop())
  }
  
  // Unified async callback
  private[this] def resume(v: Any): Unit = {
    cur = popCont(v)
    schedule()
  }
  
  private[this] val kAsync: Either[Throwable, Any] => Unit = {
    case Right(v) => resume(v)
    case Left(e) => completeExceptionally(e)
  }
  
  // Trampolined interpreter
  private[this] def runLoop(): Unit = {
    var ops = 0
    try {
      while ((cur ne null) && !cancelled && !done.isDone) {
        // Fairness gate
        if ((ops & 1023) == 0 && ops > 0 && executorQueueNonEmpty()) {
          pending = true
          return
        }
        
        cur match {
          case fm: FlatMapTask[_, _] =>
            conts.addLast(fm.contAny)
            cur = fm.source.asInstanceOf[Task[Any]]
          
          case UnitTask =>
            cur = popCont(())
            
          case p: PureTask[_] =>
            cur = popCont(p.value)
            
          case s: SingleTask[_] =>
            try {
              val v = s.f()
              cur = popCont(v)
            } catch {
              case e: Throwable => completeExceptionally(e); return
            }
            
          case e: ErrorTask[_] =>
            completeExceptionally(e.throwable); return
            
          case c: CompletableTask[_] =>
            c.onSuccess(v => resume(v))
            c.onFailure(e => completeExceptionally(e))
            cur = null; return
            
          case f: FixedThreadPoolFiber[_] =>
            f.listenEither(kAsync)
            cur = null; return
            
          case SleepTask(d) =>
            Timer.after(d.length, d.unit)(() => resume(()))
            cur = null; return
            
          case t: Taskable[_] =>
            cur = t.toTask.asInstanceOf[Task[Any]]
            
          case f: Forge[_, _] =>
            // Apply forge to previous value - this should have been handled by FlatMapTask
            throw new IllegalStateException(s"Unexpected Forge in interpreter: $f")
            
          case unknown =>
            System.err.println(s"[RUNLOOP] Fallback hit: ${unknown.getClass.getName} -> $unknown")
            throw new IllegalStateException(s"Unexpected Task node in hot loop: $unknown")
        }
        ops += 1
      }
      if ((cur eq null) && !done.isDone && conts.isEmpty) complete(())
    } finally {
      inLoop = false
      if (pending) { pending = false; schedule() }
    }
  }
  
  private[this] def popCont(value: Any): Task[Any] = {
    val cont = conts.pollLast()
    if (cont ne null) cont(value) else { complete(value); null }
  }
  
  private[this] def complete(value: Any): Unit = done.complete(value.asInstanceOf[Return])
  private[this] def completeExceptionally(error: Throwable): Unit = done.completeExceptionally(error)
  
  private[this] def executorQueueNonEmpty(): Boolean = {
    executor match {
      case tpe: ThreadPoolExecutor => !tpe.getQueue.isEmpty
      case _ => true
    }
  }
  
  // Non-blocking join support
  private[rapid] def listenEither(k: Either[Throwable, Any] => Unit): Unit = {
    done.whenComplete { (v, ex) =>
      if (ex == null) k(Right(v.asInstanceOf[Any]))
      else {
        val cause = Option(ex.getCause).getOrElse(ex)
        k(Left(cause))
      }
    }
  }
  
  override def sync(): Return = try {
    done.get()
  } catch {
    case e: java.util.concurrent.ExecutionException => throw e.getCause
    case e: Throwable => throw e
  }

  override def cancel: Task[Boolean] = Task {
    if (!cancelled) {
      cancelled = true
      done.cancel(true)
      true
    } else {
      false
    }
  }

  override def await(duration: FiniteDuration): Option[Return] = try {
    val result = done.get(duration.toMillis, TimeUnit.MILLISECONDS)
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
  
  private[rapid] lazy val executor = Executors.newFixedThreadPool(
    math.max(Runtime.getRuntime.availableProcessors(), 4),
    threadFactory
  ).asInstanceOf[ThreadPoolExecutor]
  
  private val counter = new AtomicLong(0L)
  
  // Shared timer for all sleep operations
  private object Timer {
    private val es = Executors.newScheduledThreadPool(1, r => {
      val t = new Thread(r, "rapid-timer")
      t.setDaemon(true)
      t
    })
    def after(d: Long, u: TimeUnit)(k: () => Unit): Unit =
      es.schedule(new Runnable { def run(): Unit = k() }, d, u)
  }

  def fireAndForget[Return](task: Task[Return]): Unit = new FixedThreadPoolFiber(task)
  
  def shutdown(): Unit = executor.shutdown()
}