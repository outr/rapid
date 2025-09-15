package rapid

import java.util.concurrent.{ThreadFactory, CompletableFuture, ConcurrentLinkedQueue}
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong, AtomicBoolean}
import java.util.concurrent.locks.LockSupport
import scala.util.{Try, Success, Failure}
import scala.concurrent.{Future, Promise}

/**
 * A work-stealing thread pool optimized for fiber-based task execution.
 * 
 * Key features:
 * - Each worker has its own deque for local tasks
 * - Workers steal from other workers when idle
 * - Supports fiber parking to avoid blocking threads
 * - Integrates with TimerWheel for scheduled tasks
 */
object WorkStealingPool {
  // Configuration
  private val numWorkers = math.max(sys.props.getOrElse("rapid.workers", 
    Runtime.getRuntime.availableProcessors().toString).toInt, 2)
  
  // Worker threads
  val workers = new Array[Worker](numWorkers)
  private val workersInitialized = new AtomicBoolean(false)
  
  // Global submission queue for external threads
  val globalQueue = new ConcurrentLinkedQueue[TaskWrapper]()
  
  // Statistics
  private val totalTasks = new AtomicLong(0)
  private val completedTasks = new AtomicLong(0)
  private val stolenTasks = new AtomicLong(0)
  
  // Thread-local to identify worker threads
  private val workerThreadLocal = new ThreadLocal[Worker]()
  
  /**
   * Task wrapper that includes completion handling
   */
  class TaskWrapper(
    val task: Task[_],
    val onComplete: Try[Any] => Unit
  ) extends Runnable {
    override def run(): Unit = {
      try {
        val result = task.sync()
        onComplete(Success(result))
        completedTasks.incrementAndGet()
      } catch {
        case e: Throwable =>
          onComplete(Failure(e))
          completedTasks.incrementAndGet()
      }
    }
  }
  
  /**
   * Worker thread that processes tasks from its local queue
   * and steals from others when idle.
   */
  class Worker(val id: Int) extends Thread(s"rapid-worker-$id") {
    // Local work queue
    val localQueue = new WorkStealingDeque[TaskWrapper](1024)
    
    // Parking control
    @volatile private var shouldPark = false
    private val parked = new AtomicBoolean(false)
    
    // Statistics
    private val tasksProcessed = new AtomicLong(0)
    private val tasksStolen = new AtomicLong(0)
    
    setDaemon(true)
    
    override def run(): Unit = {
      // Register this thread as a worker
      workerThreadLocal.set(this)
      // Set work-stealing context for this thread
      WorkStealingContext.setWorker(this)
      
      try {
        while (!isInterrupted) {
          val task = getNextTask()
          
          if (task != null) {
            shouldPark = false
            try {
              task.run()
              tasksProcessed.incrementAndGet()
            } catch {
              case _: InterruptedException => 
                Thread.currentThread().interrupt()
                return
              case e: Throwable =>
                // Log error but continue processing
                System.err.println(s"Worker $id error processing task: $e")
            }
          } else {
            // No work available - park this worker
            parkWorker()
          }
        }
      } finally {
        // Clear context when worker terminates
        WorkStealingContext.clearWorker()
      }
    }
    
    /**
     * Get the next task to process.
     * 
     * Priority:
     * 1. Local queue
     * 2. Global queue
     * 3. Steal from other workers
     */
    private def getNextTask(): TaskWrapper = {
      // Try local queue first
      localQueue.poll() match {
        case Some(task) => return task
        case None => // Continue
      }
      
      // Try global queue
      val globalTask = globalQueue.poll()
      if (globalTask != null) return globalTask
      
      // Try stealing from other workers
      stealTask() match {
        case Some(task) => 
          tasksStolen.incrementAndGet()
          stolenTasks.incrementAndGet()
          return task
        case None => // No work available
      }
      
      null
    }
    
    /**
     * Try to steal a task from another worker.
     */
    private def stealTask(): Option[TaskWrapper] = {
      // Start stealing from a random worker to avoid contention
      val startIdx = (System.nanoTime() % numWorkers).toInt.abs
      
      for (i <- 0 until numWorkers) {
        val idx = (startIdx + i) % numWorkers
        if (idx != id) {
          val victim = workers(idx)
          if (victim != null) {
            victim.localQueue.steal() match {
              case Some(task) => return Some(task)
              case None => // Continue searching
            }
          }
        }
      }
      
      None
    }
    
    /**
     * Park this worker when no work is available.
     */
    private def parkWorker(): Unit = {
      shouldPark = true
      
      // Double-check there's still no work
      if (!localQueue.isEmpty || !globalQueue.isEmpty) {
        shouldPark = false
        return
      }
      
      // Park for a short time
      parked.set(true)
      LockSupport.parkNanos(this, 1000000) // 1ms
      parked.set(false)
      shouldPark = false
    }
    
    /**
     * Wake up this worker if it's parked.
     */
    def wakeUp(): Unit = {
      if (parked.get()) {
        LockSupport.unpark(this)
      }
    }
    
    /**
     * Submit a task to this worker's local queue.
     */
    def submitLocal(wrapper: TaskWrapper): Boolean = {
      val submitted = localQueue.push(wrapper)
      if (submitted && shouldPark) {
        wakeUp()
      }
      submitted
    }
  }
  
  /**
   * Initialize the worker pool lazily.
   */
  private def ensureInitialized(): Unit = {
    if (!workersInitialized.get() && workersInitialized.compareAndSet(false, true)) {
      for (i <- 0 until numWorkers) {
        workers(i) = new Worker(i)
        workers(i).start()
      }
      
      // Add shutdown hook
      Runtime.getRuntime.addShutdownHook(new Thread() {
        override def run(): Unit = shutdown()
      })
    }
  }
  
  /**
   * Check if the current thread is a worker thread.
   */
  def isWorkerThread: Boolean = workerThreadLocal.get() != null
  
  /**
   * Get the current worker thread, if any.
   */
  def currentWorker: Option[Worker] = Option(workerThreadLocal.get())
  
  /**
   * Check if currently in a worker thread.
   */
  def isInWorkerThread: Boolean = workerThreadLocal.get() != null
  
  /**
   * Submit a task for execution.
   */
  def submit[T](task: Task[T]): CompletableFuture[T] = {
    ensureInitialized()
    totalTasks.incrementAndGet()
    
    val future = new CompletableFuture[T]()
    val wrapper = new TaskWrapper(
      task, 
      result => result match {
        case Success(value) => future.complete(value.asInstanceOf[T])
        case Failure(error) => future.completeExceptionally(error)
      }
    )
    
    // Try to submit to current worker's queue if in worker thread
    currentWorker match {
      case Some(worker) =>
        if (!worker.submitLocal(wrapper)) {
          // Local queue full, try another worker
          submitToAnyWorker(wrapper)
        }
      case None =>
        // External thread - submit to global queue or any worker
        submitToAnyWorker(wrapper)
    }
    
    future
  }
  
  /**
   * Submit a task and park the current fiber until completion.
   */
  def submitAndPark[T](task: Task[T], parker: FiberParker[T]): Unit = {
    ensureInitialized()
    totalTasks.incrementAndGet()
    
    val wrapper = new TaskWrapper(
      task,
      result => result match {
        case Success(value) => parker.unparkSuccess(value.asInstanceOf[T])
        case Failure(error) => parker.unparkFailure(error)
      }
    )
    
    // Submit the task
    currentWorker match {
      case Some(worker) =>
        if (!worker.submitLocal(wrapper)) {
          submitToAnyWorker(wrapper)
        }
      case None =>
        submitToAnyWorker(wrapper)
    }
  }
  
  /**
   * Submit a task asynchronously with a Scala Future.
   */
  def submitAsync[T](task: Task[T], promise: Promise[T]): Unit = {
    ensureInitialized()
    totalTasks.incrementAndGet()
    
    val wrapper = new TaskWrapper(
      task,
      result => result match {
        case Success(value) => promise.success(value.asInstanceOf[T])
        case Failure(error) => promise.failure(error)
      }
    )
    
    submitToAnyWorker(wrapper)
  }
  
  /**
   * Submit a task to any available worker.
   */
  private def submitToAnyWorker(wrapper: TaskWrapper): Unit = {
    // Try to find a worker with space
    val startIdx = (System.nanoTime() % numWorkers).toInt.abs
    
    for (i <- 0 until numWorkers) {
      val idx = (startIdx + i) % numWorkers
      val worker = workers(idx)
      if (worker != null && worker.submitLocal(wrapper)) {
        return
      }
    }
    
    // All local queues full - use global queue
    globalQueue.offer(wrapper)
    
    // Wake up a random worker to process it
    val randomWorker = workers((System.nanoTime() % numWorkers).toInt.abs)
    if (randomWorker != null) {
      randomWorker.wakeUp()
    }
  }
  
  /**
   * Execute a task directly if in worker thread, otherwise submit.
   */
  def execute[T](task: Task[T]): T = {
    if (isWorkerThread) {
      // Direct execution in worker thread
      task.sync()
    } else {
      // Submit and wait
      submit(task).get()
    }
  }
  
  /**
   * Shutdown the pool.
   */
  def shutdown(): Unit = {
    for (worker <- workers if worker != null) {
      worker.interrupt()
    }
  }
  
  /**
   * Get pool statistics.
   */
  def getStats: PoolStats = PoolStats(
    numWorkers = numWorkers,
    totalTasks = totalTasks.get(),
    completedTasks = completedTasks.get(),
    stolenTasks = stolenTasks.get(),
    queuedTasks = globalQueue.size() + workers.filter(_ != null).map(_.localQueue.size).sum
  )
  
  case class PoolStats(
    numWorkers: Int,
    totalTasks: Long,
    completedTasks: Long,
    stolenTasks: Long,
    queuedTasks: Int
  )
}