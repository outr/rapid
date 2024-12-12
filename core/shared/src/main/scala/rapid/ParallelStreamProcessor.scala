package rapid

import java.util.concurrent.ConcurrentLinkedQueue
import scala.annotation.tailrec


case class ParallelStreamProcessor[T, R](stream: ParallelStream[T, R],
                                         handle: R => Unit,
                                         complete: Int => Unit) {
  private val iterator = Stream.task(stream.stream)
  private val ready = new AtomicIndexedQueue[R](stream.maxBuffer)
  private val processing = new ConcurrentLinkedQueue[(Int, Task[R])]
  // Push iterator into processing queue
  private val queuingFiber: Fiber[Unit] = Stream.task(stream.stream).map { iterator =>
    var total = 0
    iterator.zipWithIndex.foreach {
      case (t, index) =>
        processing.add(index -> stream.f(t))
        total = index + 1
    }
    _total = total
  }.start()
  @volatile private var _total = -1

  def total: Option[Int] = if (_total == -1) None else Some(_total)

  // Create maxThreads threads to execute from processing and put into ready
  (0 until stream.maxThreads).foreach { _ =>
    Task(processRecursive()).start()
  }

  @tailrec
  private def processRecursive(): Unit = {
    // Grab next processing and execute
    val next = processing.poll
    if (next != null) {
      val (index, task) = next
      val result = task.sync()
      // Put into ready
      ready.add(index, result)
    } else {
      Thread.sleep(1)
    }
    if (_total != -1 && processing.isEmpty) {
      // Stop
    } else {
      processRecursive()
    }
  }

  // Monitor position and push results
  Task {
    var count = 0
    while (_total == -1 || count < _total) {
      ready.blockingPoll() match {
        case Some(r) =>
          handle(r)
          count += 1
        case None => Thread.sleep(1)
      }
    }
    complete(_total)
  }.start()
}

