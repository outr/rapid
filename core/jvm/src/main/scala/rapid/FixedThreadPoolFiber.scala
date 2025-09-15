package rapid

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.{Executors, Future, ScheduledExecutorService, ThreadFactory, TimeUnit, CompletableFuture}
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

object FixedThreadPoolFiber {
  
  private val defaultPoolSize = math.max(Runtime.getRuntime.availableProcessors(), 4)
  
  private def createThreadFactory(prefix: String): ThreadFactory = new ThreadFactory {
    override def newThread(r: Runnable): Thread = {
      val thread = new Thread(r)
      thread.setName(s"$prefix-${counter.incrementAndGet()}")
      thread.setDaemon(true)
      thread
    }
  }
  
  private lazy val threadFactory = createThreadFactory("rapid-ft")
  
  // Virtual thread executor for async tasks
  private lazy val virtualExecutor = {
    val threadFactory = Thread.ofVirtual().factory()
    java.util.concurrent.Executors.newThreadPerTaskExecutor(threadFactory)
  }
  
  
  // Thread pool for task execution
  private[rapid] lazy val executor = {
    Executors.newFixedThreadPool(defaultPoolSize, threadFactory)
  }
  
  // Scheduler for delayed tasks
  lazy val scheduledExecutor: ScheduledExecutorService = {
    // Use small pool size for efficiency
    Executors.newScheduledThreadPool(defaultPoolSize, createThreadFactory("rapid-scheduler"))
  }
  
  private val counter = new AtomicLong(0L)
  
  @inline private def failedFuture[T](error: Throwable): CompletableFuture[T] = {
    val future = new CompletableFuture[T]()
    future.completeExceptionally(error)
    future
  }
  
  @inline private def executeContinuation[Return](continuation: Task[Return]): Future[Return] = {
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
  }
  
  private def create[Return](task: Task[Return]): Future[Return] = {
    // CRITICAL: Always ensure at least one async hop to prevent stack overflow
    // This matches cats-effect's approach where IO.async forces a thread shift
    // to break the synchronous call chain and prevent stack overflow in deep
    // recursive operations like flatMap chains.
    // 
    // Unlike sync() which can optimize with inline execution for performance,
    // async operations MUST go through the executor to ensure stack safety.
    // This separation allows us to have both:
    // - Safe async execution (no stack overflow)
    // - Fast sync execution (inline optimizations in sync() method)
    task match {
      case SleepTask(d) =>
        // Sleep can be handled directly with scheduler (still async)
        val javaFuture = new CompletableFuture[Return]()
        scheduledExecutor.schedule(new Runnable {
          def run(): Unit = javaFuture.complete(().asInstanceOf[Return])
        }, d.toMillis, TimeUnit.MILLISECONDS)
        javaFuture
      case _ =>
        // Always go through executor for async hop - even for PureTask/UnitTask
        // This prevents stack overflow in deep flatMap chains
        val javaFuture = new CompletableFuture[Return]()
        executor.submit(new Runnable {
          def run(): Unit = executeCallback(task, javaFuture.complete, javaFuture.completeExceptionally)
        })
        javaFuture
    }
  }
  
  private def createWithDepth[Return](task: Task[Return], depth: Int): Future[Return] = {
    // Prevent infinite recursion
    if (depth > 10) {
      // Fall back to callback system if too deep
      val javaFuture = new CompletableFuture[Return]()
      
      executeCallback(
        task,
        javaFuture.complete,
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
            
            scheduledExecutor.schedule(new Runnable {
              def run(): Unit = {
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
              }
            }, duration.toMillis, TimeUnit.MILLISECONDS)
            
            javaFuture
            
          // UNWRAPPING: FlatMapTask(PureTask) - common from .map on pure values
          case pure: PureTask[_] =>
            try {
              // Apply forge directly to the pure value
              val continuation = flatMap.asInstanceOf[FlatMapTask[Any, Return]].forge.asInstanceOf[rapid.Forge[Any, Return]](pure.value)
              // Recursively unwrap the result with depth tracking - DON'T create intermediate future
              createWithDepth(continuation, depth + 1)
            } catch {
              case error: Throwable => failedFuture[Return](error)
            }
            
          // UNWRAPPING: FlatMapTask(UnitTask) - common from .map on unit
          case _: UnitTask =>
            try {
              // Apply forge to unit value
              val continuation = flatMap.asInstanceOf[FlatMapTask[Any, Return]].forge.asInstanceOf[rapid.Forge[Any, Return]](())
              // Recursively unwrap the result with depth tracking - DON'T create intermediate future
              createWithDepth(continuation, depth + 1)
            } catch {
              case error: Throwable => failedFuture[Return](error)
            }
            
          case _ =>
            // Non-sleep, non-pure FlatMapTask - use original callback system
            val javaFuture = new CompletableFuture[Return]()
            
            executeCallback(
              task,
              javaFuture.complete,
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
              executeContinuation(continuation)
            } catch {
              case error: Throwable => failedFuture[Return](error)
            }
            
          // DirectFlatMapTask(UnitTask) - common pattern
          case _: UnitTask =>
            try {
              val continuation = direct.asInstanceOf[DirectFlatMapTask[Any, Return]].f(())
              executeContinuation(continuation)
            } catch {
              case error: Throwable => failedFuture[Return](error)
            }
            
          // DirectFlatMapTask(SleepTask) - optimize sleep chains
          case SleepTask(duration) =>
            val javaFuture = new CompletableFuture[Return]()
            scheduledExecutor.schedule(new Runnable {
              def run(): Unit = {
                try {
                  val continuation = direct.asInstanceOf[DirectFlatMapTask[Any, Return]].f(())
                  // Always submit back to executor for proper async execution
                  executor.submit(new Runnable {
                    def run(): Unit = executeCallback(continuation, javaFuture.complete, javaFuture.completeExceptionally)
                  })
                } catch {
                  case error: Throwable => javaFuture.completeExceptionally(error)
                }
              }
            }, duration.toMillis, TimeUnit.MILLISECONDS)
            javaFuture
            
          // Other sources - use callback system
          case _ =>
            val javaFuture = new CompletableFuture[Return]()
            
            executeCallback(
              task,
              javaFuture.complete,
              error => {
                javaFuture.completeExceptionally(error)
              }
            )
            
            javaFuture
        }
      
      // ALL OTHER TASKS: Use original callback system
      case _ =>
        val javaFuture = new CompletableFuture[Return]()
        
        executeCallback(
          task,
          javaFuture.complete,
          error => {
            javaFuture.completeExceptionally(error)
          }
        )
        
        javaFuture
    }
  }

  def fireAndForget[Return](task: Task[Return]): Unit = {
    // ORIGINAL PATH ONLY - no fast paths for debugging
    executeCallback(task, (_: Return) => (), (_: Throwable) => ())
  }
  
  
  /**
   * Delegate to shared execution engine.
   * Package-private to allow reuse by VirtualThreadFiber.
   */
  private[rapid] def executeCallback[Return](
    task: Task[Return],
    onSuccess: Return => Unit,
    onFailure: Throwable => Unit
  ): Unit = {
    SharedExecutionEngine.executeCallback(
      task, 
      onSuccess, 
      onFailure,
      Some((task: () => Unit) => virtualExecutor.execute(new Runnable {
        override def run(): Unit = task()
      }))
    )
  }
  
  // Shutdown method for cleanup
  def shutdown(): Unit = {
    executor.shutdown()
    scheduledExecutor.shutdown()
    virtualExecutor.shutdown()
  }
  
  // Accessor for JDKScheduledSleep to use the scheduled executor service
  def scheduledExecutorService: ScheduledExecutorService = scheduledExecutor
}
