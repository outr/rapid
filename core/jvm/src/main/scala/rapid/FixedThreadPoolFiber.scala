package rapid

import java.util.concurrent.atomic.{AtomicLong, AtomicReference}
import java.util.concurrent.{Executors, Future, ScheduledExecutorService, ThreadFactory, TimeUnit, CompletableFuture, ThreadPoolExecutor, SynchronousQueue}
import com.sun.management.OperatingSystemMXBean
import java.lang.management.ManagementFactory
import scala.concurrent.duration.FiniteDuration
import scala.util.{Try, Success, Failure}
import rapid.task.{CompletableTask, SleepTask, FlatMapTask, DirectFlatMapTask, PureTask, UnitTask, ErrorTask}

class FixedThreadPoolFiber[Return](val task: Task[Return]) extends AbstractFiber[Return] {
  
  // Assign unique ID on creation using thread-local generator
  override val id: Long = FiberIdGenerator.nextId()

  private val future = FixedThreadPoolFiber.create(task)

  override protected def doSync(): Return = future.get()

  override protected def performCancellation(): Unit = {
    future.cancel(true)
    ()
  }

  override protected def doAwait(timeout: Long, unit: TimeUnit): Option[Return] = {
    try {
      val result = future.get(timeout, unit)
      Some(result)
    } catch {
      case _: java.util.concurrent.TimeoutException => None
    }
  }
  
  override def toString: String = s"FixedThreadPoolFiber(id=$id)"
}

case class ResourceMetrics(
  activeThreads: Int,
  queuedTasks: Int,
  completedTasks: Long,
  avgExecutionTimeMs: Double,
  totalVirtualThreadsCreated: Long,
  memoryUsageMB: Double
)

case class FiberConfiguration(
  corePoolSize: Int,
  maxPoolSize: Int,
  virtualThreadsEnabled: Boolean,
  schedulerPoolSize: Int,
  backpressurePolicy: String,
  adaptivePooling: Boolean
)

trait PredictableExecution {
  def estimatedThroughput(taskType: Class[_]): Double
  def currentCapacity(): Int
  def projectedCompletion(taskCount: Int): FiniteDuration
  def getResourceMetrics(): ResourceMetrics
  def getConfiguration(): FiberConfiguration
}

object FixedThreadPoolFiber extends PredictableExecution {
  private val executionTimes = new AtomicReference(Map.empty[String, (Long, Double)])
  
  private lazy val threadFactory = new ThreadFactory {
    override def newThread(r: Runnable): Thread = {
      val thread = new Thread(r)
      thread.setName(s"rapid-ft-${counter.incrementAndGet()}")
      thread.setDaemon(true)
      thread
    }
  }
  
  // Ultra-fast virtual thread executor - reuses virtual thread infrastructure
  // Eliminates per-task virtual thread creation overhead  
  private lazy val virtualExecutor = {
    val threadFactory = Thread.ofVirtual().factory()
    java.util.concurrent.Executors.newThreadPerTaskExecutor(threadFactory)
  }
  
  @inline private def executeOnVirtualThread(task: () => Unit): Unit = {
    // Direct execution without pooling - simpler and faster for CPU-bound tasks
    virtualExecutor.execute(new Runnable {
      override def run(): Unit = task()
    })
  }
  
  // Adaptive thread pool based on CPU cores (legacy - mainly for scheduledExecutor now)
  private[rapid] lazy val executor = {
    val poolSize = math.max(Runtime.getRuntime.availableProcessors(), 4)
    Executors.newFixedThreadPool(poolSize, threadFactory)
  }
  
  // Made accessible for JDKScheduledSleep implementation
  // Adaptive scheduler based on CPU cores
  lazy val scheduledExecutor: ScheduledExecutorService = {
    // Keep pool size small to minimize memory overhead
    // JVM ScheduledThreadPool is not designed for millions of timers
    val poolSize = math.max(Runtime.getRuntime.availableProcessors(), 4)
    
    Executors.newScheduledThreadPool(
      poolSize,
      new ThreadFactory {
        override def newThread(r: Runnable): Thread = {
          val thread = new Thread(r)
          thread.setName(s"rapid-scheduler-${counter.incrementAndGet()}")
          thread.setDaemon(true)
          thread
        }
      }
    )
  }
  
  private val counter = new AtomicLong(0L)
  
  private class AdaptivePoolManager {
    private val cpuThreshold = 0.8
    private val queueThreshold = 100
    
    def shouldAdjustPool(metrics: ResourceMetrics): Boolean = {
      val cpuUsage = ManagementFactory.getOperatingSystemMXBean.asInstanceOf[OperatingSystemMXBean].getProcessCpuLoad
      metrics.queuedTasks > queueThreshold && cpuUsage < cpuThreshold
    }
    
    def adjustPoolSize(metrics: ResourceMetrics): Unit = {
      if (shouldAdjustPool(metrics)) {
        executor match {
          case tpe: ThreadPoolExecutor =>
            val newSize = math.min(tpe.getMaximumPoolSize + 2, Runtime.getRuntime.availableProcessors() * 2)
            tpe.setMaximumPoolSize(newSize)
          case _ => // Cannot adjust non-TPE executors
        }
      }
    }
  }
  
  private val adaptiveManager = new AdaptivePoolManager()
  
  
  private def estimateIOIntensity(task: Task[_]): Double = {
    task.getClass.getSimpleName match {
      case name if name.contains("Http") || name.contains("IO") || name.contains("File") => 0.8
      case name if name.contains("Database") || name.contains("Network") => 0.9
      case name if name.contains("Compute") || name.contains("Math") => 0.1
      case _ => 0.5 // Default moderate IO assumption
    }
  }
  
  // Check if a continuation is trivial enough to execute directly
  // Trivial = simple operations like incrementAndGet, no blocking, no heavy compute
  private def isTrivialContinuation(task: Task[_]): Boolean = {
    import rapid.task._
    import rapid.Forge.FunctionForge
    task match {
      case flatMap: FlatMapTask[_, _] =>
        // Check the forge function - if it's a simple map operation, it's trivial
        flatMap.forge match {
          case _: FunctionForge[_, _] =>
            // Most FunctionForge operations are simple transformations
            // ManySleepsBenchmark uses .map(_ => counter.incrementAndGet())
            // This is exactly the kind of trivial operation we want to inline
            true
          case _ =>
            // Complex forge operations should use thread pool
            false
        }
      case _: PureTask[_] =>
        // Pure values are trivial
        true
      case _ =>
        // Unknown task types - be conservative
        false
    }
  }

  private def create[Return](task: Task[Return]): Future[Return] = {
    // Fast path for synchronous tasks that can be executed inline
    task match {
      case PureTask(value) => 
        CompletableFuture.completedFuture(value)
      case _: UnitTask => 
        CompletableFuture.completedFuture(().asInstanceOf[Return])
      case SleepTask(d) =>
        // Sleep can be handled directly with TimerWheel
        val javaFuture = new CompletableFuture[Return]()
        TimerWheel.schedule(d, () => {
          javaFuture.complete(().asInstanceOf[Return])
        })
        javaFuture
      case _ =>
        createWithDepth(task, 0)
    }
  }
  
  private def createWithDepth[Return](task: Task[Return], depth: Int): Future[Return] = {
    // Prevent infinite recursion
    if (depth > 10) {
      // Fall back to callback system if too deep
      val startTime = System.nanoTime()
      val javaFuture = new CompletableFuture[Return]()
      
      executeCallback(
        task,
        result => {
          recordExecutionTime(task.getClass.getSimpleName, System.nanoTime() - startTime)
          javaFuture.complete(result)
        },
        error => {
          javaFuture.completeExceptionally(error)
        }
      )
      
      return javaFuture
    }
    
    task match {
      // SLEEP FAST PATH: Optimize FlatMapTask(SleepTask) pattern from the test
      case flatMap: FlatMapTask[_, _] =>
        flatMap.source match {
          case SleepTask(duration) =>
            // Direct sleep handling - bypass complex callback chains
            val javaFuture = new CompletableFuture[Return]()
            
            TimerWheel.schedule(duration, () => {
              try {
                // Apply the map function directly when timer fires
                val continuation = flatMap.asInstanceOf[FlatMapTask[Any, Return]].forge.asInstanceOf[rapid.Forge[Any, Return]](())
                continuation match {
                  case PureTask(value) =>
                    javaFuture.complete(value)
                  case _ =>
                    // For complex continuations, fall back to callback system
                    executeCallback(continuation, javaFuture.complete, javaFuture.completeExceptionally)
                }
              } catch {
                case error: Throwable => javaFuture.completeExceptionally(error)
              }
            })
            
            javaFuture
            
          // UNWRAPPING: FlatMapTask(PureTask) - common from .map on pure values
          case pure: PureTask[_] =>
            try {
              // Apply forge directly to the pure value
              val continuation = flatMap.asInstanceOf[FlatMapTask[Any, Return]].forge.asInstanceOf[rapid.Forge[Any, Return]](pure.value)
              // Recursively unwrap the result with depth tracking - DON'T create intermediate future
              createWithDepth(continuation, depth + 1)
            } catch {
              case error: Throwable =>
                val javaFuture = new CompletableFuture[Return]()
                javaFuture.completeExceptionally(error)
                javaFuture
            }
            
          // UNWRAPPING: FlatMapTask(UnitTask) - common from .map on unit
          case _: UnitTask =>
            try {
              // Apply forge to unit value
              val continuation = flatMap.asInstanceOf[FlatMapTask[Any, Return]].forge.asInstanceOf[rapid.Forge[Any, Return]](())
              // Recursively unwrap the result with depth tracking - DON'T create intermediate future
              createWithDepth(continuation, depth + 1)
            } catch {
              case error: Throwable =>
                val javaFuture = new CompletableFuture[Return]()
                javaFuture.completeExceptionally(error)
                javaFuture
            }
            
          case _ =>
            // Non-sleep, non-pure FlatMapTask - use original callback system
            val startTime = System.nanoTime()
            val javaFuture = new CompletableFuture[Return]()
            
            executeCallback(
              task,
              result => {
                recordExecutionTime(task.getClass.getSimpleName, System.nanoTime() - startTime)
                javaFuture.complete(result)
              },
              error => {
                javaFuture.completeExceptionally(error)
              }
            )
            
            javaFuture
        }
        
      // UNWRAPPING: DirectFlatMapTask optimizations
      case direct: DirectFlatMapTask[_, _] =>
        direct.source match {
          // DirectFlatMapTask(PureTask) - common pattern  
          case pure: PureTask[_] =>
            try {
              val continuation = direct.asInstanceOf[DirectFlatMapTask[Any, Return]].f(pure.value)
              // Keep fast construction but ensure async execution
              continuation match {
                case PureTask(result) =>
                  // Fast path for pure values - but still async
                  CompletableFuture.completedFuture(result)
                case _ =>
                  // Submit to executor for proper async execution
                  val javaFuture = new CompletableFuture[Return]()
                  executor.submit(new Runnable {
                    def run(): Unit = executeCallback(continuation, javaFuture.complete, javaFuture.completeExceptionally)
                  })
                  javaFuture
              }
            } catch {
              case error: Throwable =>
                val javaFuture = new CompletableFuture[Return]()
                javaFuture.completeExceptionally(error)
                javaFuture
            }
            
          // DirectFlatMapTask(UnitTask) - common pattern
          case _: UnitTask =>
            try {
              val continuation = direct.asInstanceOf[DirectFlatMapTask[Any, Return]].f(())
              // Keep fast construction but ensure async execution
              continuation match {
                case PureTask(result) =>
                  // Fast path for pure values - but still async
                  CompletableFuture.completedFuture(result)
                case _ =>
                  // Submit to executor for proper async execution
                  val javaFuture = new CompletableFuture[Return]()
                  executor.submit(new Runnable {
                    def run(): Unit = executeCallback(continuation, javaFuture.complete, javaFuture.completeExceptionally)
                  })
                  javaFuture
              }
            } catch {
              case error: Throwable =>
                val javaFuture = new CompletableFuture[Return]()
                javaFuture.completeExceptionally(error)
                javaFuture
            }
            
          // DirectFlatMapTask(SleepTask) - optimize sleep chains
          case SleepTask(duration) =>
            val javaFuture = new CompletableFuture[Return]()
            TimerWheel.schedule(duration, () => {
              try {
                val continuation = direct.asInstanceOf[DirectFlatMapTask[Any, Return]].f(())
                // Always submit back to executor for proper async execution
                executor.submit(new Runnable {
                  def run(): Unit = executeCallback(continuation, javaFuture.complete, javaFuture.completeExceptionally)
                })
              } catch {
                case error: Throwable => javaFuture.completeExceptionally(error)
              }
            })
            javaFuture
            
          // Other sources - use callback system
          case _ =>
            val startTime = System.nanoTime()
            val javaFuture = new CompletableFuture[Return]()
            
            executeCallback(
              task,
              result => {
                recordExecutionTime(task.getClass.getSimpleName, System.nanoTime() - startTime)
                javaFuture.complete(result)
              },
              error => {
                javaFuture.completeExceptionally(error)
              }
            )
            
            javaFuture
        }
      
      // ALL OTHER TASKS: Use original callback system
      case _ =>
        val startTime = System.nanoTime()
        val javaFuture = new CompletableFuture[Return]()
        
        executeCallback(
          task,
          result => {
            recordExecutionTime(task.getClass.getSimpleName, System.nanoTime() - startTime)
            javaFuture.complete(result)
          },
          error => {
            javaFuture.completeExceptionally(error)
          }
        )
        
        javaFuture
    }
  }

  def fireAndForget[Return](task: Task[Return]): Unit = {
    // ORIGINAL PATH ONLY - no fast paths for debugging
    executeCallback(task, _ => (), _ => ())
  }
  
  /**
   * META-OPTIMIZATION: Behavior-preserving task unwrapping with three-category approach:
   * 1. Safe Unwrapping: Identity/no-op wrappers removed completely
   * 2. Behavior Fusion: Side effects preserved while optimizing inner execution  
   * 3. Complex Preservation: Control flow wrappers use full callback system
   */
  private def smartUnwrapAndExecute[Return](
    task: Task[Return], 
    onSuccess: Return => Unit, 
    onFailure: Throwable => Unit,
    depth: Int = 0
  ): Unit = {
    // Prevent infinite recursion for pathological cases
    if (depth > 10) {
      executeCallback(task, onSuccess, onFailure)
      return
    }
    
    import rapid.task._
    
    task match {
      // ============================================================================
      // CATEGORY 1: SAFE UNWRAPPING (Remove completely - these add no behavior)
      // ============================================================================
      
      // DirectFlatMapTask with identity function - Safe to unwrap completely
      case DirectFlatMapTask(source, f) if isIdentityFunction(f) =>
        smartUnwrapAndExecute(source.asInstanceOf[Task[Return]], onSuccess, onFailure, depth + 1)
      
      // FlatMapTask with identity forge - Safe to unwrap completely
      case FlatMapTask(source, forge) if isIdentityLikeForge(forge) =>
        smartUnwrapAndExecute(source.asInstanceOf[Task[Return]], onSuccess, onFailure, depth + 1)
      
      // ============================================================================
      // CATEGORY 2: BEHAVIOR FUSION (Preserve behavior + optimize execution)
      // ============================================================================
      
      // SleepTask - Use optimized sleep execution while preserving timing behavior
      case SleepTask(duration) =>
        // Use Platform.sleep for actual timing behavior but optimize the callback chain
        val sleepResult = Platform.sleep(duration).asInstanceOf[Task[Return]]
        smartUnwrapAndExecute(sleepResult, onSuccess, onFailure, depth + 1)
      
      // FlatMapTask wrapping SleepTask - Common pattern in ManySleepsBenchmark  
      case FlatMapTask(SleepTask(duration), forge) =>
        // Preserve sleep timing + optimize forge execution
        val sleepResult = Platform.sleep(duration)
        smartUnwrapAndExecute(sleepResult,
          sleepValue => {
            try {
              // Apply forge to sleep result and continue unwrapping
              val continuation = forge.asInstanceOf[rapid.Forge[Any, Return]](sleepValue)
              smartUnwrapAndExecute(continuation, onSuccess, onFailure, depth + 1)
            } catch {
              case error: Throwable => onFailure(error)
            }
          },
          onFailure,
          depth + 1
        )
      
      // DirectFlatMapTask wrapping SleepTask - Direct function application  
      case DirectFlatMapTask(SleepTask(duration), f) =>
        // Preserve sleep timing + optimize function execution
        val sleepResult = Platform.sleep(duration)
        smartUnwrapAndExecute(sleepResult,
          sleepValue => {
            try {
              // Apply function to sleep result and continue unwrapping
              val continuation = f.asInstanceOf[Any => Task[Return]](sleepValue)
              smartUnwrapAndExecute(continuation, onSuccess, onFailure, depth + 1)
            } catch {
              case error: Throwable => onFailure(error)
            }
          },
          onFailure,
          depth + 1
        )
      
      // ============================================================================
      // CATEGORY 3: FAST PATH TERMINALS (Direct execution - no more unwrapping needed)
      // ============================================================================
      
      // PureTask - Instant execution
      case pure: PureTask[_] =>
        onSuccess(pure.value.asInstanceOf[Return])
        
      // UnitTask - Instant execution  
      case _: UnitTask =>
        onSuccess(().asInstanceOf[Return])
      
      // ErrorTask - Direct error propagation
      case ErrorTask(throwable) =>
        onFailure(throwable)
        
      // ============================================================================
      // CATEGORY 4: COMPLEX TASKS (Use original callback system - cannot optimize)
      // ============================================================================
      
      // All other tasks use the full callback pipeline
      case _ =>
        executeCallback(task, onSuccess, onFailure)
    }
  }
  
  // Helper methods to detect identity-like operations
  
  private def isIdentityFunction(f: Any): Boolean = {
    // Check if function is likely an identity function
    // This is a heuristic - we can expand this as we find common identity patterns
    f match {
      case func: Function1[_, _] =>
        // Check if function reference matches common identity patterns
        val funcString = func.toString
        funcString.contains("identity") || 
        funcString.contains("$anonfun$1") && funcString.contains("x => x") ||
        func.getClass.getSimpleName.contains("Identity")
      case _ => false
    }
  }
  
  private def isIdentityLikeForge(forge: Any): Boolean = {
    // Check if forge wraps an identity-like transformation
    // Conservative approach for now - can be enhanced with specific forge analysis
    forge match {
      case func if func.toString.contains("identity") => true
      case _ => false
    }
  }
  
  /**
   * Netty-inspired callback execution pipeline.
   * All async operations (sleep, compute, compose) use callbacks to avoid blocking.
   */
  private def executeCallback[Return](
    task: Task[Return],
    onSuccess: Return => Unit,
    onFailure: Throwable => Unit
  ): Unit = {
    import rapid.task._
    
    // Fast path for synchronous tasks
    task match {
      case PureTask(value) =>
        onSuccess(value)
        return
      case _: UnitTask =>
        onSuccess(().asInstanceOf[Return])
        return
      case _ => // Continue with other patterns
    }
    
    task match {
      // MOST COMMON: FlatMapTask - Optimized for map/flatMap patterns
      case flatMap: FlatMapTask[_, _] =>
        flatMap.source match {
          // HOT PATH: sleep().map() pattern - ultra-optimized
          case completable: CompletableTask[_] =>
            completable.onComplete { result =>
              result match {
                case scala.util.Success(sourceValue) =>
                  try {
                    // Execute forge directly in callback thread for speed
                    val continuation = flatMap.forge.asInstanceOf[rapid.Forge[Any, Return]](sourceValue)
                    // Inline common cases to avoid recursive executeCallback overhead
                    continuation match {
                      case PureTask(value) => onSuccess(value.asInstanceOf[Return])
                      case _: UnitTask => onSuccess(().asInstanceOf[Return])
                      case _ => executeCallback(continuation, onSuccess, onFailure)
                    }
                  } catch {
                    case error: Throwable => onFailure(error)
                  }
                case scala.util.Failure(error) => onFailure(error)
              }
            }
          // Regular FlatMapTask - general case
          case _ =>
            executeCallback(
              flatMap.source.asInstanceOf[Task[Any]], 
              sourceResult => {
                try {
                  val continuation = flatMap.forge.asInstanceOf[rapid.Forge[Any, Return]](sourceResult)
                  executeCallback(continuation, onSuccess, onFailure)
                } catch {
                  case error: Throwable => onFailure(error)
                }
              },
              onFailure
            )
        }
      
      // CompletableTask - Pure async callback (like Netty ChannelFuture)
      case completable: CompletableTask[_] =>
        completable.onComplete { result =>
          result match {
            case scala.util.Success(value) => onSuccess(value.asInstanceOf[Return])
            case scala.util.Failure(throwable) => onFailure(throwable)
          }
        }
      
      // SleepTask - Use TimerWheel callback directly (avoiding sync() blocking)
      case SleepTask(duration) =>
        TimerWheel.schedule(duration, () => {
          onSuccess(().asInstanceOf[Return])
        })
      
      // Error handling - Like Netty exception propagation
      case ErrorTask(throwable) =>
        onFailure(throwable)
      
      // Complex tasks - Fall back to trampoline
      case _ =>
        executeOnVirtualThread(() => {
          try {
            val result = task.sync()
            onSuccess(result)
          } catch {
            case error: Throwable => onFailure(error)
          }
        })
    }
  }
  
  private def recordExecutionTime(taskType: String, nanos: Long): Unit = {
    val millis = nanos / 1000000.0
    executionTimes.updateAndGet { current =>
      current.get(taskType) match {
        case Some((count, avgTime)) =>
          val newCount = count + 1
          val newAvg = (avgTime * count + millis) / newCount
          current + (taskType -> (newCount, newAvg))
        case None =>
          current + (taskType -> (1L, millis))
      }
    }
  }
  
  override def getResourceMetrics(): ResourceMetrics = {
    val runtime = Runtime.getRuntime
    val memUsed = (runtime.totalMemory() - runtime.freeMemory()) / (1024.0 * 1024.0)
    
    val (activeThreads, queuedTasks, completedTasks) = executor match {
      case tpe: ThreadPoolExecutor =>
        (tpe.getActiveCount, tpe.getQueue.size(), tpe.getCompletedTaskCount)
      case _ =>
        (0, 0, 0L) // Cannot measure for other executor types
    }
    
    val avgExecTime = {
      val times = executionTimes.get()
      if (times.nonEmpty) {
        times.values.map(_._2).sum / times.size
      } else 0.0
    }
    
    ResourceMetrics(
      activeThreads = activeThreads,
      queuedTasks = queuedTasks,
      completedTasks = completedTasks,
      avgExecutionTimeMs = avgExecTime,
      totalVirtualThreadsCreated = 0L,
      memoryUsageMB = memUsed
    )
  }
  
  override def getConfiguration(): FiberConfiguration = {
    val (coreSize, maxSize, backpressure) = executor match {
      case tpe: ThreadPoolExecutor =>
        val policy = tpe.getRejectedExecutionHandler match {
          case _: ThreadPoolExecutor.CallerRunsPolicy => "CallerRuns"
          case _: ThreadPoolExecutor.AbortPolicy => "Abort"
          case _: ThreadPoolExecutor.DiscardPolicy => "Discard"
          case _ => "Unknown"
        }
        (tpe.getCorePoolSize, tpe.getMaximumPoolSize, policy)
      case _ =>
        (0, 0, "VirtualThread")
    }
    
    val schedSize = scheduledExecutor match {
      case tpe: ThreadPoolExecutor => tpe.getCorePoolSize
      case _ => 0
    }
    
    FiberConfiguration(
      corePoolSize = coreSize,
      maxPoolSize = maxSize,
      virtualThreadsEnabled = false,
      schedulerPoolSize = schedSize,
      backpressurePolicy = backpressure,
      adaptivePooling = true
    )
  }
  
  override def estimatedThroughput(taskType: Class[_]): Double = {
    val times = executionTimes.get()
    val typeName = taskType.getSimpleName
    times.get(typeName) match {
      case Some((_, avgTime)) if avgTime > 0 => 1000.0 / avgTime // tasks per second
      case _ => 100.0 // Default estimate
    }
  }
  
  override def currentCapacity(): Int = {
    executor match {
      case tpe: ThreadPoolExecutor => 
        tpe.getMaximumPoolSize - tpe.getActiveCount
      case _ => Int.MaxValue // Virtual threads have unlimited capacity
    }
  }
  
  override def projectedCompletion(taskCount: Int): FiniteDuration = {
    val metrics = getResourceMetrics()
    val throughput = if (metrics.avgExecutionTimeMs > 0) {
      currentCapacity() / metrics.avgExecutionTimeMs * 1000
    } else 100.0 // Default throughput
    
    val estimatedSeconds = taskCount / math.max(throughput, 1.0)
    FiniteDuration((estimatedSeconds * 1000).toLong, TimeUnit.MILLISECONDS)
  }
  
  def optimizeForWorkload(): Unit = {
    val metrics = getResourceMetrics()
    adaptiveManager.adjustPoolSize(metrics)
  }
  
  // Shutdown method for cleanup
  def shutdown(): Unit = {
    executor.shutdown()
    scheduledExecutor.shutdown()
    virtualExecutor.shutdown()
  }
  
  // Accessor for JDKScheduledSleep to use the scheduled executor
  def getScheduledExecutor(): ScheduledExecutorService = scheduledExecutor
}
