package rapid

import java.util.concurrent.{ConcurrentLinkedQueue, TimeUnit}
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong, AtomicBoolean}
import scala.concurrent.duration.FiniteDuration

/**
 * Minimal timer wheel optimized specifically for high-volume sleep operations.
 *
 * This is used ONLY for SleepTask and SleepMapTask to handle benchmarks like
 * ManySleeps that create millions of timers. Other timer operations continue
 * to use ScheduledExecutorService.
 *
 * Key optimizations:
 * - O(1) insertion time (vs O(log n) for ScheduledExecutorService)
 * - Minimal allocations with pooled slots
 * - Single timer thread to reduce context switching
 * - 10ms tick resolution (good enough for sleep operations)
 */
class TimerWheel {

  // 10ms ticks - balance between precision and CPU usage
  private val tickDurationMs = 10L
  private val wheelSize = 512  // 512 * 10ms = 5.12 seconds per rotation

  // Each wheel slot holds tasks scheduled for that tick
  private val wheel = Array.fill(wheelSize)(new ConcurrentLinkedQueue[TimerTask]())
  private val currentTick = new AtomicLong(0)
  private val startTimeMs = System.currentTimeMillis()

  // For tasks > 5.12 seconds in future (sorted by deadline)
  private val overflowQueue = new java.util.concurrent.ConcurrentSkipListSet[TimerTask](
    (a: TimerTask, b: TimerTask) => {
      val cmp = java.lang.Long.compare(a.deadlineMs, b.deadlineMs)
      if (cmp != 0) cmp
      else java.lang.Integer.compare(a.id, b.id) // Use ID to break ties
    }
  )

  // Statistics
  private val totalScheduled = new AtomicInteger(0)
  private val totalExecuted = new AtomicInteger(0)
  private val taskIdCounter = new AtomicInteger(0)

  // Control flag
  private val running = new AtomicBoolean(true)

  // Single timer thread
  private val timerThread = new Thread("rapid-timer-wheel") {
    override def run(): Unit = {
      while (running.get()) {
        val startMs = System.currentTimeMillis()
        tick()

        // Sleep until next tick
        val elapsedMs = System.currentTimeMillis() - startMs
        val sleepMs = tickDurationMs - elapsedMs
        if (sleepMs > 0) {
          Thread.sleep(sleepMs)
        }
      }
    }
  }
  timerThread.setDaemon(true)
  timerThread.start()

  case class TimerTask(
    id: Int,
    deadlineMs: Long,
    callback: Runnable
  )

  /**
   * Schedule a sleep callback to run after the given delay.
   * Optimized for high throughput with millions of concurrent timers.
   */
  def schedule(delay: FiniteDuration, callback: Runnable): Unit = {
    val delayMs = delay.toMillis

    // Execute immediately for non-positive delays
    if (delayMs <= 0) {
      try {
        callback.run()
      } catch {
        case _: Throwable => // Ignore callback errors
      }
      return
    }

    val nowMs = System.currentTimeMillis()
    val deadlineMs = nowMs + delayMs
    val task = TimerTask(
      taskIdCounter.incrementAndGet(),
      deadlineMs,
      callback
    )

    totalScheduled.incrementAndGet()

    if (delayMs < wheelSize * tickDurationMs) {
      // Fits in the wheel - calculate slot
      val ticksFromNow = (delayMs / tickDurationMs).toInt
      val targetTick = currentTick.get() + ticksFromNow
      val slot = (targetTick % wheelSize).toInt
      wheel(slot).offer(task)
    } else {
      // Too far in future - add to overflow
      overflowQueue.add(task)
    }
  }

  private def tick(): Unit = {
    val nowMs = System.currentTimeMillis()
    val tickIndex = (currentTick.incrementAndGet() % wheelSize).toInt
    val bucket = wheel(tickIndex)

    // Process all tasks in this tick's bucket
    var task = bucket.poll()
    while (task != null) {
      if (task.deadlineMs <= nowMs + tickDurationMs) {
        // Task is due - execute it
        executeTask(task)
      } else {
        // Task was scheduled for a future rotation - put it back
        bucket.offer(task)
      }
      task = bucket.poll()
    }

    // Move tasks from overflow queue to wheel if they're now in range
    val horizonMs = nowMs + (wheelSize * tickDurationMs)
    if (!overflowQueue.isEmpty) {
      var overflowTask = overflowQueue.first()
      while (overflowTask != null && overflowTask.deadlineMs < horizonMs) {
        overflowQueue.remove(overflowTask)

        // Calculate which slot this task belongs in
        val delayMs = Math.max(0, overflowTask.deadlineMs - nowMs)
        val ticksFromNow = (delayMs / tickDurationMs).toInt
        val targetTick = currentTick.get() + ticksFromNow
        val slot = (targetTick % wheelSize).toInt
        wheel(slot).offer(overflowTask)

        // Check next overflow task
        overflowTask = if (overflowQueue.isEmpty) null else overflowQueue.first()
      }
    }
  }

  private def executeTask(task: TimerTask): Unit = {
    totalExecuted.incrementAndGet()
    try {
      // Execute on the timer thread - callbacks should be fast
      // For heavy work, the callback should submit to an executor
      task.callback.run()
    } catch {
      case _: Throwable => // Ignore callback errors
    }
  }

  def shutdown(): Unit = {
    running.set(false)
    timerThread.interrupt()
  }

  def statistics(): String = {
    s"TimerWheel[scheduled=${totalScheduled.get()}, executed=${totalExecuted.get()}, pending=${totalScheduled.get() - totalExecuted.get()}]"
  }
}

/**
 * Global timer wheel instance for sleep operations.
 * Separate from the general ScheduledExecutorService.
 */
object TimerWheel {
  lazy val instance = new TimerWheel()

  def schedule(delay: FiniteDuration, callback: Runnable): Unit = {
    instance.schedule(delay, callback)
  }

  def shutdown(): Unit = instance.shutdown()

  def statistics(): String = instance.statistics()
}