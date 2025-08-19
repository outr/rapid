package rapid.scheduler

import rapid.runtime.ReadyQueue
import java.util.concurrent.{Executors, ThreadFactory, ExecutorService}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import scala.reflect.Selectable.reflectiveSelectable

/** Minimal hashed-wheel timer with a single daemon thread.
  * Expired sleeps are enqueued to ReadyQueue, and exactly one drain task per tick is submitted to the pool.
  */
final class HashedWheelTimer2(
  tickMillis: Int,
  wheelSize: Int,
  ready: ReadyQueue,
  pool: ExecutorService,                 // FixedThreadPoolFiber.executor
  drainBatch: Int = 1024
) extends Timer2 {

  private[this] final class Node(var deadlineNanos: Long, var fiber: AnyRef, var cont: AnyRef) {
    @volatile var canceled = false
    var next: Node = null
    // prev no longer needed
  }

  private[this] final class Bucket {
    private var head: Node = null

    /** Thread-safe push at head. */
    def add(n: Node): Unit = this.synchronized {
      n.next = head
      head = n
    }

    /** Move expired to ready queue; if queue is full, keep the node for next tick. */
    def expire(now: Long): Int = this.synchronized {
      var count = 0
      var in = head
      var kept: Node = null
      while (in != null) {
        val nx = in.next
        if (!in.canceled && in.deadlineNanos <= now) {
          if (ready.offer(in.fiber, in.cont)) {
            count += 1
          } else {
            // queue full: keep it to retry next tick (no loss, no spin)
            in.next = kept; kept = in
          }
        } else if (!in.canceled) {
          in.next = kept; kept = in
        }
        in = nx
      }
      head = kept
      if (probe && count > 0) System.err.println(s"[P3] expired=$count @now=$now")
      count
    }
  }

  private val wheel = Array.fill(wheelSize)(new Bucket)
  private val tickNanos = tickMillis.toLong * 1000L * 1000L
  private val startNanos = System.nanoTime()
  private val cursor = new AtomicInteger(0)
  private val running = new AtomicBoolean(true)
  private val drainScheduled = new AtomicBoolean(false)

  // near top of HashedWheelTimer2
  private val probe = java.lang.Boolean.getBoolean("rapid.p3.probe")

  private val timerThread = {
    val tf = new ThreadFactory {
      def newThread(r: Runnable): Thread = {
        val t = new Thread(r, "rapid-wheel-timer")
        t.setDaemon(true)
        t
      }
    }
    Executors.newSingleThreadExecutor(tf)
  }

  // Timer tick loop
  timerThread.submit(new Runnable {
    def run(): Unit = {
      var nextTick = startNanos + tickNanos
      while (running.get()) {
        val now = System.nanoTime()
        val sleep = nextTick - now
        if (sleep > 0) {
          try Thread.sleep(Math.min(sleep / 1000000L, tickMillis).toLong)
          catch { case _: InterruptedException => () }
        } else {
          val idx = Math.floorMod(cursor.getAndIncrement(), wheelSize)  // explicit
          val expired = wheel(idx).expire(now)
          // schedule a drain irrespective of `expired` so a full queue gets serviced
          if (drainScheduled.compareAndSet(false, true)) {
            // One drain task per tick max
            pool.execute(new Runnable {
              def run(): Unit = {
                try {
                  if (probe) System.err.println("[P3] drain scheduled")
                  var more = true
                  while (more) {
                    val drained = ready.drain(drainBatch)((fiber, cont) => {
                      // Resume fiber immediately on this pool task (no reflection)
                      fiber.asInstanceOf[rapid.runtime.TimerWakeable].__externalResumeFromTimer(cont)
                    })
                    more = drained == drainBatch
                  }
                } finally {
                  drainScheduled.set(false)
                }
              }
            })
          }
          nextTick += tickNanos
        }
      }
    }
  })

  override def nowNanos(): Long = System.nanoTime()

  override def registerSleep(deadlineNanos: Long, fiber: AnyRef, cont: AnyRef): CancelToken = {
    val n = new Node(deadlineNanos, fiber, cont)
    val ticks = (deadlineNanos - startNanos) / tickNanos   // Long
    val slot  = Math.floorMod(ticks.toInt, wheelSize)      // safe modulo at the end
    wheel(slot).add(n)
    new CancelToken { def cancel(): Unit = n.canceled = true }
  }

  override def shutdown(): Unit = {
    running.set(false)
    timerThread.shutdown()
  }
}