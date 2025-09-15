package rapid

import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec

/**
 * Cooperative yielding support for Task.sync() when running in work-stealing threads.
 * 
 * This allows blocking operations to yield control back to the worker thread
 * to process other tasks while waiting for completion.
 */
object CooperativeYielding {
  
  /**
   * Execute a blocking operation with cooperative yielding if in a worker thread.
   * 
   * @param blockingOp The blocking operation that might need to wait
   * @param isReady Check if the operation is complete
   * @param getResult Get the result once ready
   * @return The result of the operation
   */
  def yieldingSync[T](
    blockingOp: => T,
    isReady: => Boolean,
    getResult: => T
  ): T = {
    if (WorkStealingContext.isInWorkerThread) {
      // We're in a worker thread - yield cooperatively
      cooperativeWait(isReady, getResult)
    } else {
      // External thread - must block traditionally
      blockingOp
    }
  }
  
  /**
   * Cooperatively wait for a condition to become true.
   * Yields control back to the worker thread between checks.
   */
  @tailrec
  private def cooperativeWait[T](isReady: => Boolean, getResult: => T): T = {
    if (isReady) {
      getResult
    } else {
      // Yield to process other tasks
      WorkStealingContext.getWorker match {
        case Some(worker) =>
          // Process one task from our queue if available
          val task = worker.localQueue.poll()
          if (task.isDefined) {
            try {
              task.get.run()
            } catch {
              case _: InterruptedException =>
                Thread.currentThread().interrupt()
                throw new InterruptedException("Worker interrupted during cooperative yield")
              case e: Throwable =>
                // Log but continue - don't let one task failure break yielding
                System.err.println(s"Error during cooperative yield: $e")
            }
          } else {
            // No local work - try stealing
            val stolen = stealOneTask(worker)
            if (stolen.isDefined) {
              try {
                stolen.get.run()
              } catch {
                case _: InterruptedException =>
                  Thread.currentThread().interrupt()
                  throw new InterruptedException("Worker interrupted during cooperative yield")
                case e: Throwable =>
                  System.err.println(s"Error during cooperative yield: $e")
              }
            } else {
              // No work available - brief pause to avoid busy-waiting
              Thread.`yield`()
            }
          }
          
          // Check again
          cooperativeWait(isReady, getResult)
          
        case None =>
          // Shouldn't happen, but fallback to blocking
          while (!isReady) {
            Thread.`yield`()
          }
          getResult
      }
    }
  }
  
  /**
   * Try to steal one task from another worker.
   */
  private def stealOneTask(worker: WorkStealingPool.Worker): Option[WorkStealingPool.TaskWrapper] = {
    val numWorkers = WorkStealingPool.getStats.numWorkers
    val startIdx = (System.nanoTime() % numWorkers).toInt.abs
    
    for (i <- 0 until numWorkers) {
      val idx = (startIdx + i) % numWorkers
      if (idx != worker.id) {
        val victim = WorkStealingPool.workers(idx)
        if (victim != null) {
          val stolen = victim.localQueue.steal()
          if (stolen.isDefined) {
            return stolen
          }
        }
      }
    }
    
    // Also check global queue
    Option(WorkStealingPool.globalQueue.poll())
  }
  
  /**
   * Create a future-based cooperative completion.
   * This wraps a CompletableFuture to support cooperative yielding.
   */
  def cooperativeFuture[T](future: CompletableFuture[T]): T = {
    yieldingSync(
      blockingOp = future.get(),
      isReady = future.isDone,
      getResult = {
        try {
          future.getNow(null.asInstanceOf[T])
        } catch {
          case ex: Throwable if future.isCompletedExceptionally =>
            // Extract the actual exception
            try {
              future.get() // This will throw the wrapped exception
              throw new IllegalStateException("Should not reach here")
            } catch {
              case e: java.util.concurrent.ExecutionException => throw e.getCause
              case e: Throwable => throw e
            }
        }
      }
    )
  }
}