package rapid.fiber

import java.util.concurrent.{ConcurrentHashMap, ConcurrentLinkedQueue}

private object SleepScheduler {
  private val queues = new ConcurrentHashMap[Long, ConcurrentLinkedQueue[Runnable]]()

  private val timer = new Thread(() => loop(), "rapid-v2-sleep-timer")
  timer.setDaemon(true)
  timer.start()

  def schedule(atMillis: Long, r: Runnable): Unit = {
    val sec = atMillis / 1000L
    val q = queues.computeIfAbsent(sec, _ => new ConcurrentLinkedQueue[Runnable]())
    q.add(r)
  }

  private def loop(): Unit = {
    while (true) {
      val now = System.currentTimeMillis()
      val sec = now / 1000L
      val q = queues.remove(sec)
      if (q ne null) {
        var t = q.poll()
        while (t ne null) {
          try FixedThreadPoolFiber.executor.execute(t)
          catch { case _: Throwable => () }
          t = q.poll()
        }
      }
      // sleep until next second boundary
      val next = ((sec + 1) * 1000L) - now
      if (next > 0) Thread.sleep(next)
    }
  }
}



