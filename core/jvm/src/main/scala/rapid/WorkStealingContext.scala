package rapid

/**
 * Context for detecting if code is running in a work-stealing worker thread.
 * This allows sync() operations to cooperatively yield instead of blocking.
 */
object WorkStealingContext {
  private val inWorkerThread = new ThreadLocal[Boolean] {
    override def initialValue(): Boolean = false
  }
  
  private val currentWorker = new ThreadLocal[WorkStealingPool.Worker]
  
  /**
   * Check if the current thread is a work-stealing worker thread.
   */
  def isInWorkerThread: Boolean = inWorkerThread.get()
  
  /**
   * Set whether the current thread is a work-stealing worker thread.
   */
  def setInWorkerThread(value: Boolean): Unit = inWorkerThread.set(value)
  
  /**
   * Get the current worker if in a worker thread.
   */
  def getWorker: Option[WorkStealingPool.Worker] = Option(currentWorker.get())
  
  /**
   * Set the current worker for this thread.
   */
  def setWorker(worker: WorkStealingPool.Worker): Unit = {
    currentWorker.set(worker)
    setInWorkerThread(true)
  }
  
  /**
   * Clear the worker context for this thread.
   */
  def clearWorker(): Unit = {
    currentWorker.remove()
    setInWorkerThread(false)
  }
  
  /**
   * Execute a task with work-stealing context.
   */
  def withWorker[T](worker: WorkStealingPool.Worker)(f: => T): T = {
    val previousInWorker = isInWorkerThread
    val previousWorker = currentWorker.get()
    try {
      setWorker(worker)
      f
    } finally {
      if (previousWorker != null) {
        currentWorker.set(previousWorker)
        setInWorkerThread(previousInWorker)
      } else {
        clearWorker()
      }
    }
  }
}