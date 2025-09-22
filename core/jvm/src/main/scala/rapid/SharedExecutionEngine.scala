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
                    // Type cast safe: FlatMapTask[_, Return] ensures forge produces Return
                    val continuation = flatMap.forge.asInstanceOf[rapid.Forge[Any, Return]](sourceValue)
                    // Inline common cases to avoid recursive executeCallback overhead
                    continuation match {
                      case rapid.task.PureTask(value) =>
                        // Safe cast: PureTask[Return] ensures value is of type Return
                        onSuccess(value.asInstanceOf[Return])
                      case _: rapid.task.UnitTask =>
                        // Safe cast: UnitTask context ensures Return =:= Unit
                        onSuccess(().asInstanceOf[Return])
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
                  // Type cast safe: FlatMapTask[_, Return] ensures forge produces Return
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
            case Success(value) =>
              // Safe cast: Task[Return] execution ensures value is of type Return
              onSuccess(value.asInstanceOf[Return])
            case Failure(throwable) => onFailure(throwable)
          }
        }
      
      // SleepTask - Use TimerWheel for O(1) insertion (avoiding sync() blocking)
      case rapid.task.SleepTask(duration) =>
        TimerWheel.schedule(duration, new Runnable {
          def run(): Unit = {
            // Safe cast: SleepTask returns Unit, cast valid when Return =:= Unit
            onSuccess(().asInstanceOf[Return])
          }
        })

      // SleepMapTask - Fused sleep+map operation (critical for ManySleeps performance)
      case rapid.task.SleepMapTask(duration, mapFunc) =>
        TimerWheel.schedule(duration, new Runnable {
          def run(): Unit = {
            try {
              // Execute the composed map function after sleep completes
              val result = mapFunc()
              // Safe cast: SleepMapTask[Return] ensures mapFunc produces Return type
              onSuccess(result.asInstanceOf[Return])
            } catch {
              case ex: Throwable => onFailure(ex)
            }
          }
        })

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
            executor(() => {
              // Safe cast: UnitTask returns Unit, cast valid when Return =:= Unit
              onSuccess(().asInstanceOf[Return])
            })
          case None =>
            // Safe cast: UnitTask returns Unit, cast valid when Return =:= Unit
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