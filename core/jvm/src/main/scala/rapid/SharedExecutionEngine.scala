package rapid

import java.util.concurrent.TimeUnit
import scala.util.{Success, Failure}

/**
 * Shared execution engine for all Fiber implementations.
 * Contains optimized callback execution patterns that were previously
 * locked inside FixedThreadPoolFiber.
 */
object SharedExecutionEngine {
  
  /**
   * Netty-inspired callback execution pipeline.
   * All async operations (sleep, compute, compose) use callbacks to avoid blocking.
   */
  def executeCallback[Return](
    task: Task[Return],
    onSuccess: Return => Unit,
    onFailure: Throwable => Unit,
    executeOnVirtualThread: Option[(() => Unit) => Unit] = None
  ): Unit = {
    
    task match {
      // MOST COMMON: FlatMapTask - Optimized for map/flatMap patterns
      case flatMap: rapid.task.FlatMapTask[_, _] =>
        flatMap.source match {
          // HOT PATH: sleep().map() pattern - ultra-optimized
          case completable: rapid.task.CompletableTask[_] =>
            completable.onComplete { result =>
              result match {
                case Success(sourceValue) =>
                  try {
                    // Execute forge directly in callback thread for speed
                    val continuation = flatMap.forge.asInstanceOf[rapid.Forge[Any, Return]](sourceValue)
                    // Inline common cases to avoid recursive executeCallback overhead
                    continuation match {
                      case rapid.task.PureTask(value) => onSuccess(value.asInstanceOf[Return])
                      case _: rapid.task.UnitTask => onSuccess(().asInstanceOf[Return])
                      case _ => executeCallback(continuation, onSuccess, onFailure, executeOnVirtualThread)
                    }
                  } catch {
                    case error: Throwable => onFailure(error)
                  }
                case Failure(error) => onFailure(error)
              }
            }
          // Regular FlatMapTask - general case
          case _ =>
            executeCallback(
              flatMap.source.asInstanceOf[Task[Any]], 
              (sourceResult: Any) => {
                try {
                  val continuation = flatMap.forge.asInstanceOf[rapid.Forge[Any, Return]](sourceResult)
                  executeCallback(continuation, onSuccess, onFailure, executeOnVirtualThread)
                } catch {
                  case error: Throwable => onFailure(error)
                }
              },
              onFailure,
              executeOnVirtualThread
            )
        }
      
      // CompletableTask - Pure async callback (like Netty ChannelFuture)
      case completable: rapid.task.CompletableTask[_] =>
        completable.onComplete { result =>
          result match {
            case Success(value) => onSuccess(value.asInstanceOf[Return])
            case Failure(throwable) => onFailure(throwable)
          }
        }
      
      // SleepTask - Use TimerWheel callback directly (avoiding sync() blocking)
      case rapid.task.SleepTask(duration) =>
        FixedThreadPoolFiber.scheduledExecutor.schedule(new Runnable {
          def run(): Unit = onSuccess(().asInstanceOf[Return])
        }, duration.toMillis, TimeUnit.MILLISECONDS)
      
      // Error handling - Like Netty exception propagation
      case rapid.task.ErrorTask(throwable) =>
        onFailure(throwable)
      
      // PureTask and UnitTask - ensure async boundary when executor available
      case rapid.task.PureTask(value) =>
        executeOnVirtualThread match {
          case Some(executor) =>
            executor(() => onSuccess(value))
          case None =>
            onSuccess(value)
        }
      
      case _: rapid.task.UnitTask =>
        executeOnVirtualThread match {
          case Some(executor) =>
            executor(() => onSuccess(().asInstanceOf[Return]))
          case None =>
            onSuccess(().asInstanceOf[Return])
        }
      
      // Complex tasks - Fall back to trampoline or direct execution
      case _ =>
        executeOnVirtualThread match {
          case Some(executor) =>
            executor(() => {
              try {
                val result = task.sync()
                onSuccess(result)
              } catch {
                case error: Throwable => onFailure(error)
              }
            })
          case None =>
            // Direct execution when no executor provided
            try {
              val result = task.sync()
              onSuccess(result)
            } catch {
              case error: Throwable => onFailure(error)
            }
        }
    }
  }
}