package rapid

import java.util.concurrent.{ConcurrentLinkedQueue, ScheduledExecutorService, TimeUnit, Executors}
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}
import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration

/**
 * Precise timer wheel implementation that handles millions of timers efficiently.
 * This is THE single timer solution for all of Rapid.
 * 
 * Key features:
 * - Stores exact nanosecond deadlines for precision
 * - O(1) insertion for most timers
 * - Handles both short and long delays efficiently
 * - No blocking, all callbacks are async
 */
class TimerWheel {
  
  // Use 1ms ticks for precision
  private val tickDurationMs = 1L
  private val wheelSize = 1024  // 1024ms = ~1 second per wheel rotation
  
  private val wheel = Array.fill(wheelSize)(new ConcurrentLinkedQueue[TimerTask]())
  private val currentTick = new AtomicLong(0)
  private val startTimeNanos = System.nanoTime()
  
  // Overflow list for timers > 1 second (sorted by deadline)
  private val overflowQueue = new java.util.PriorityQueue[TimerTask](
    (a: TimerTask, b: TimerTask) => java.lang.Long.compare(a.deadlineNanos, b.deadlineNanos)
  )
  private val overflowLock = new Object()
  
  // Statistics
  private val totalTimers = new AtomicInteger(0)
  private val expiredTimers = new AtomicInteger(0)
  
  // Single timer thread - more efficient than ScheduledExecutor for our use case
  private val timerThread = new Thread("rapid-timer-wheel") {
    override def run(): Unit = {
      while (!Thread.currentThread().isInterrupted) {
        val startNanos = System.nanoTime()
        tick()
        
        // Sleep until next tick (1ms resolution)
        val elapsedNanos = System.nanoTime() - startNanos
        val sleepNanos = (tickDurationMs * 1_000_000) - elapsedNanos
        if (sleepNanos > 0) {
          val sleepMillis = sleepNanos / 1_000_000
          val remainingNanos = (sleepNanos % 1_000_000).toInt
          Thread.sleep(sleepMillis, remainingNanos)
        }
      }
    }
  }
  timerThread.setDaemon(true)
  timerThread.start()
  
  case class TimerTask(
    deadlineNanos: Long,  // Exact nanosecond deadline for precision
    callback: () => Unit
  )
  
  /**
   * Schedule a task to run after the given delay.
   * This is the ONLY timer method - used everywhere in Rapid.
   */
  def schedule(delay: FiniteDuration, callback: () => Unit): Unit = {
    if (delay.toMillis <= 0) {
      // Execute immediately
      try {
        callback()
      } catch {
        case _: Throwable => // Ignore callback errors
      }
      return
    }
    
    val nowNanos = System.nanoTime()
    val deadlineNanos = nowNanos + delay.toNanos
    val task = TimerTask(deadlineNanos, callback)
    
    val delayMs = delay.toMillis
    val currentTickValue = currentTick.get()
    
    val slot = if (delayMs < wheelSize * tickDurationMs) {
      // Fits in the wheel - add to appropriate slot
      // Convert delay milliseconds to ticks (each tick = 1ms)
      val ticksFromNow = (delayMs / tickDurationMs).toInt
      val targetTick = currentTickValue + ticksFromNow
      val s = (targetTick % wheelSize).toInt
      wheel(s).offer(task)
      s
    } else {
      // Too far in future - add to overflow queue
      overflowLock.synchronized {
        overflowQueue.offer(task)
      }
      -1
    }
    
    totalTimers.incrementAndGet()
  }
  
  /**
   * Process one tick of the wheel
   * Returns true if any work was done (timers executed or pending)
   */
  private def tick(): Boolean = {
    val nowNanos = System.nanoTime()
    val tickNum = currentTick.incrementAndGet()
    val tickIndex = (tickNum % wheelSize).toInt
    val bucket = wheel(tickIndex)
    
    var hadWork = false
    val activeTimers = totalTimers.get() - expiredTimers.get()
    
    
    // Process current wheel slot
    
    val iterator = bucket.iterator()
    while (iterator.hasNext) {
      val task = iterator.next()
      // Check EXACT deadline - this is critical!
      // We must check nanos, not just assume tasks in this slot are ready
      val remainingNanos = task.deadlineNanos - nowNanos
      if (remainingNanos <= 0) {
        iterator.remove()
        executeCallback(task.callback)
        hadWork = true
      } else {
        // Task not ready yet - it will stay for next wheel rotation
        // This handles tasks that were scheduled far in advance
      }
    }
    
    // Check overflow queue and move tasks to wheel if they're within range
    overflowLock.synchronized {
      var continueProcessing = true
      while (!overflowQueue.isEmpty && continueProcessing) {
        val task = overflowQueue.peek()
        if (task.deadlineNanos <= nowNanos) {
          // Ready to execute
          overflowQueue.poll()
          executeCallback(task.callback)
          hadWork = true
        } else {
          // Check if we can move it to the wheel
          val remainingNanos = task.deadlineNanos - nowNanos
          val remainingMs = remainingNanos / 1_000_000
          
          if (remainingMs < wheelSize) {
            // Now fits in wheel
            overflowQueue.poll()
            val slot = ((currentTick.get() + remainingMs) % wheelSize).toInt
            wheel(slot).offer(task)
          } else {
            // Still too far, leave in overflow
            // Queue is sorted, so no need to check further
            continueProcessing = false
          }
        }
      }
    }
    
    // Return true if we have any pending timers
    hadWork || activeTimers > 0
  }
  
  // Small dedicated executor for timer callbacks to prevent deadlock
  // Uses minimal threads since callbacks just call completable.success()
  private lazy val callbackExecutor = {
    val threadFactory = new java.util.concurrent.ThreadFactory {
      private val counter = new java.util.concurrent.atomic.AtomicInteger(0)
      override def newThread(r: Runnable): Thread = {
        val t = new Thread(r, s"timer-callback-${counter.incrementAndGet()}")
        t.setDaemon(true)
        t
      }
    }
    // Use fixed pool with just 2 threads - callbacks are very fast
    java.util.concurrent.Executors.newFixedThreadPool(2, threadFactory)
  }
  
  private def executeCallback(callback: () => Unit): Unit = {
    expiredTimers.incrementAndGet()
    
    // Execute callbacks on dedicated executor to prevent deadlock
    callbackExecutor.submit(new Runnable {
      override def run(): Unit = {
        try {
          callback()
        } catch {
          case _: Throwable => 
            // Ignore callback errors
        }
      }
    })
  }
  
  def shutdown(): Unit = {
    timerThread.interrupt()
    callbackExecutor.shutdown()
  }
  
  def statistics: String = {
    val overflow = overflowLock.synchronized { overflowQueue.size() }
    s"Total timers: ${totalTimers.get()}, Expired: ${expiredTimers.get()}, Overflow: $overflow"
  }
}

/**
 * Global timer wheel instance - THE single timer for all of Rapid
 */
object TimerWheel {
  lazy val instance = new TimerWheel()
  
  def schedule(delay: FiniteDuration, callback: () => Unit): Unit = {
    instance.schedule(delay, callback)
  }
  
  def shutdown(): Unit = instance.shutdown()
  
  def statistics: String = instance.statistics
}