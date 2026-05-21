package rapid.fiber

import rapid.task.{Completable, FlatMap, HandleError, Pure, Sleep, Suspend, Taskable, UnitTask}
import rapid.trace.Trace
import rapid.{Fiber, Platform, Task}

import scala.collection.mutable
import scala.util.{Failure, Success, Try}

private[rapid] sealed trait ErrorHandler
private[rapid] case class ErrorHandlerMarker(handler: Throwable => Task[Any], trace: Trace) extends ErrorHandler

class SynchronousFiber[Return](task: Task[Return]) extends Fiber[Return] {
  private val tracing = Trace.Enabled

  private var lastTrace: Trace = Trace.empty
  private val stack = new mutable.ArrayDeque[Any]
  private var previous: Any = ()
  @volatile private var suspended = false
  @volatile private var _result: Option[Try[Any]] = None
  private var completionCallbacks: List[Try[Any] => Unit] = Nil
  private val callbackLock = new AnyRef
  // Serializes runLoop() so a resumed evaluation can never iterate concurrently
  // with an in-flight one. See resume() for the full rationale.
  private val runLock = new AnyRef

  stack.append(task)
  runLoop()

  private def collectTraces(): List[Trace] = {
    val seen = new mutable.LinkedHashSet[Trace]
    if (lastTrace != Trace.empty) seen += lastTrace
    // Snapshot the stack to avoid IndexOutOfBoundsException from concurrent mutation
    // (resume() can schedule runLoop() on another thread, which calls removeLast()).
    val snapshot = stack.toArray
    var i = snapshot.length - 1
    while (i >= 0) {
      snapshot(i) match {
        case tr: Trace if tr != Trace.empty => seen += tr
        case ErrorHandlerMarker(_, tr) if tr != Trace.empty => seen += tr
        case _ =>
      }
      i -= 1
    }
    seen.toList
  }

  private def applyTraces(t: Throwable): Throwable = {
    if (tracing) Trace.update(t, collectTraces()) else t
  }

  private def runLoop(): Unit = runLock.synchronized {
    try {
      while (stack.nonEmpty && !suspended) {
        handle(stack.removeLast())
      }
      if (stack.isEmpty && !suspended) {
        completeWith(Success(previous))
      }
    } catch {
      case t: Throwable =>
        val error = applyTraces(t)
        if (findAndApplyErrorHandler(error)) {
          runLoop()
        } else {
          completeWith(Failure(error))
        }
    }
  }

  private def findAndApplyErrorHandler(t: Throwable): Boolean = {
    while (stack.nonEmpty) {
      stack.removeLast() match {
        case ErrorHandlerMarker(handler, _) =>
          try {
            stack.append(handler(t))
            return true
          } catch {
            case _: MatchError =>
              // Partial function didn't match — continue searching for another handler
            case handlerError: Throwable =>
              // Handler itself threw — convert to Task.error so it propagates through the task chain
              stack.append(Task.error(handlerError))
              return true
          }
        case _ =>
      }
    }
    false
  }

  private def handle(value: Any): Unit = value match {
    case task: Task[_] =>
      task match {
        case _: UnitTask =>
          previous = ()
        case t: Taskable[_] => handle(t.toTask)
        case Pure(value) =>
          previous = value
        case Suspend(f, tr) =>
          if (tracing) lastTrace = tr
          previous = f()
        case Sleep(duration, tr) =>
          if (tracing) lastTrace = tr
          if (Platform.isVirtualThread) {
            Platform.delay(duration.toMillis)
            previous = ()
          } else {
            suspended = true
            Platform.scheduleDelay(duration.toMillis) { () =>
              resume(())
            }
          }
        case c: Completable[_] =>
          if (tracing) lastTrace = c.trace
          c.result match {
            case Some(Success(v)) =>
              previous = v
            case Some(Failure(t)) =>
              throw t
            case None =>
              suspended = true
              c.onComplete {
                case Success(v) => resume(v)
                case Failure(t) => resumeWithError(t)
              }
          }
        case HandleError(inner, handler, tr) =>
          if (tracing) lastTrace = tr
          stack.append(ErrorHandlerMarker(handler.asInstanceOf[Throwable => Task[Any]], tr))
          stack.append(inner)
        case FlatMap(input, f, tr) =>
          if (tracing) lastTrace = tr
          input match {
            case Pure(v) =>
              previous = v
              if (tracing) stack.append(tr)
              stack.append(f.asInstanceOf[Any => Task[Any]](v))
            case Suspend(s, tr2) =>
              if (tracing) lastTrace = tr2
              previous = s()
              if (tracing) stack.append(tr)
              stack.append(f.asInstanceOf[Any => Task[Any]](previous))
            case _: UnitTask =>
              previous = ()
              if (tracing) stack.append(tr)
              stack.append(f.asInstanceOf[Any => Task[Any]](()))
            case _ =>
              if (tracing) {
                stack.append(tr)
                stack.append((f, tr))
              } else {
                stack.append(f)
              }
              stack.append(input)
          }
      }
    case _: ErrorHandlerMarker =>
      ()
    case _: Trace =>
      ()
    case f: Function1[_, _] =>
      val next = f.asInstanceOf[Any => Task[Any]](previous)
      next match {
        case Pure(v) => previous = v
        case Suspend(s, tr2) =>
          if (tracing) lastTrace = tr2
          previous = s()
        case _: UnitTask => previous = ()
        case _ => stack.append(next)
      }
    case (f: Function1[_, _], tr: Trace) =>
      if (tracing) lastTrace = tr
      val next = f.asInstanceOf[Any => Task[Any]](previous)
      next match {
        case Pure(v) => previous = v
        case Suspend(s, tr2) =>
          if (tracing) lastTrace = tr2
          previous = s()
        case _: UnitTask => previous = ()
        case _ => stack.append(next)
      }
  }

  // runLoop() holds runLock for its entire execution, and resume / resumeWithError
  // perform ALL fiber-state mutation (previous, suspended, stack) under that same
  // lock. This fully serializes fiber evaluation: a resume that races an in-flight
  // runLoop blocks until that runLoop has observed `suspended` and returned, so two
  // threads can never iterate runLoop()/handle() concurrently.
  //
  // The earlier approach — mutating inside Platform.schedule's block without a lock
  // — was insufficient: a synchronous Completable.onComplete callback (fired when the
  // completable was already resolved at registration time) reaches resume() on a
  // second thread, which flipped `suspended` to false while the suspending runLoop
  // was still between handle() and its `while (... && !suspended)` re-check. That
  // runLoop then kept iterating in parallel with the resumed one, corrupting
  // `previous` — the fiber would return a stale intermediate value, surfacing later
  // as a ClassCastException when a caller cast the result to the task's real type.
  private def resume(result: Any): Unit = {
    Platform.schedule { () =>
      runLock.synchronized {
        previous = result
        suspended = false
        runLoop()
      }
    }
  }

  private def resumeWithError(t: Throwable): Unit = {
    Platform.schedule { () =>
      runLock.synchronized {
        suspended = false
        val error = applyTraces(t)
        if (findAndApplyErrorHandler(error)) {
          runLoop()
        } else {
          completeWith(Failure(error))
        }
      }
    }
  }

  private def completeWith(result: Try[Any]): Unit = callbackLock.synchronized {
    if (_result.isDefined) return
    _result = Some(result)
    val cbs = completionCallbacks
    completionCallbacks = Nil
    cbs.foreach { cb =>
      try cb(result)
      catch { case _: Throwable => () }
    }
    callbackLock.notifyAll()
  }

  def isComplete: Boolean = _result.isDefined

  override def sync(): Return = Platform.awaitFiber(this)

  override def join: Task[Return] = {
    val c = Task.completable[Return]
    onComplete {
      case Success(v) => c.success(v)
      case Failure(t) => c.failure(t)
    }
    c
  }

  override def onComplete(f: Try[Return] => Unit): Unit = callbackLock.synchronized {
    _result match {
      case Some(r) => f(r.asInstanceOf[Try[Return]])
      case None =>
        completionCallbacks = ((r: Try[Any]) => f(r.asInstanceOf[Try[Return]])) :: completionCallbacks
    }
  }

  private[rapid] def awaitBlocking(): Return = callbackLock.synchronized {
    while (_result.isEmpty) callbackLock.wait()
    _result.get match {
      case Success(r) => r.asInstanceOf[Return]
      case Failure(t) => throw t
    }
  }

  private[rapid] def resultOpt: Option[Try[Return]] = _result.asInstanceOf[Option[Try[Return]]]
}

object SynchronousFiber {
  def apply[Return](task: Task[Return]): SynchronousFiber[Return] = new SynchronousFiber(task)
}
