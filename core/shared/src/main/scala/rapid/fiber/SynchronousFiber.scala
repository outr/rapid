package rapid.fiber

import rapid.task.{Completable, FlatMap, HandleError, Pure, Sleep, Suspend, Taskable, UnitTask}
import rapid.trace.Trace
import rapid.{Fiber, Platform, Task}

import scala.collection.mutable
import scala.util.{Failure, Success, Try}

private[rapid] sealed trait ErrorHandler
private[rapid] case class ErrorHandlerMarker(handler: Throwable => Task[Any], trace: Trace) extends ErrorHandler

class SynchronousFiber[Return](task: Task[Return]) extends Fiber[Return] {
  private val TraceBufSize = 256
  private val tracing = Trace.Enabled
  private val traceBuf: Array[Trace] = if (tracing) new Array[Trace](TraceBufSize) else null
  private var traceIdx = 0

  @inline private def record(tr: Trace): Unit = if (tracing && tr != Trace.empty) {
    traceBuf(traceIdx & (TraceBufSize - 1)) = tr
    traceIdx += 1
  }

  private var lastTrace: Trace = Trace.empty
  private val stack = new mutable.ArrayDeque[Any]
  private var previous: Any = ()
  @volatile private var suspended = false
  @volatile private var _result: Option[Try[Any]] = None
  private var completionCallbacks: List[Try[Any] => Unit] = Nil
  private val callbackLock = new AnyRef

  stack.append(task)
  runLoop()

  private def runLoop(): Unit = {
    try {
      while (stack.nonEmpty && !suspended) {
        handle(stack.removeLast())
      }
      if (stack.isEmpty && !suspended) {
        completeWith(Success(previous))
      }
    } catch {
      case t: Throwable =>
        val error = if (tracing) {
          val n = math.min(traceIdx, TraceBufSize)
          val frames =
            List.tabulate(n)(i => traceBuf((traceIdx - 1 - i) & (TraceBufSize - 1)))
              .filter(_ != Trace.empty)
              .distinct
          Trace.update(t, frames)
        } else t
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
          stack.append(handler(t))
          return true
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
          suspended = true
          Platform.scheduleDelay(duration.toMillis) { () =>
            resume(())
          }
        case c: Completable[_] =>
          if (tracing) {
            lastTrace = c.trace
            record(c.trace)
          }
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
          if (tracing) {
            lastTrace = tr
            record(tr)
          }
          stack.append(ErrorHandlerMarker(handler.asInstanceOf[Throwable => Task[Any]], tr))
          stack.append(inner)
        case FlatMap(input, f, tr) =>
          if (tracing) stack.append((f, tr)) else stack.append(f)
          stack.append(input)
      }
    case _: ErrorHandlerMarker =>
      ()
    case f: Function1[_, _] =>
      val next = f.asInstanceOf[Any => Task[Any]](previous)
      stack.append(next)
    case (f: Function1[_, _], tr: Trace) =>
      if (tracing) {
        lastTrace = tr
        record(tr)
      }
      val next = f.asInstanceOf[Any => Task[Any]](previous)
      stack.append(next)
  }

  private def resume(result: Any): Unit = {
    previous = result
    suspended = false
    Platform.schedule(() => runLoop())
  }

  private def resumeWithError(t: Throwable): Unit = {
    suspended = false
    Platform.schedule { () =>
      completeWith(Failure(t))
    }
  }

  private def completeWith(result: Try[Any]): Unit = callbackLock.synchronized {
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
