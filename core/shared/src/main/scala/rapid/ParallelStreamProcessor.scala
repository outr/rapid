package rapid

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import scala.annotation.tailrec

case class ParallelStreamProcessor[T, R](stream: ParallelStream[T, R],
                                         handle: R => Unit,
                                         complete: Int => Unit) {
  private val iteratorTask: Task[Iterator[T]] = Stream.task(stream.stream)

  private val ready = new AtomicIndexedQueue[R](stream.maxBuffer)
  private val processing = new ConcurrentLinkedQueue[(Int, Task[R])]
  @volatile private var _total = -1

  // Start a fiber that consumes the stream and queues tasks
  private val queuingFiber: Fiber[Unit] = iteratorTask.map { iterator =>
    var total = 0
    iterator.zipWithIndex.foreach {
      case (t, index) =>
        processing.add(index -> stream.f(t))
        total = index + 1
    }
    _total = total
  }.start()

  def total: Option[Int] = if (_total == -1) None else Some(_total)

  // Spawn worker fibers to process tasks
  (0 until stream.maxThreads).foreach { _ =>
    Task(processRecursive()).start()
  }

  @tailrec
  private def processRecursive(): Unit = {
    val next = processing.poll()
    if (next != null) {
      val (index, task) = next
      val result = task.sync()
      ready.add(index, result)
    } else {
      // No next task
      Thread.sleep(1) // Consider a better signaling mechanism
    }
    // If total known and no more tasks, stop recursion
    if (_total != -1 && processing.isEmpty) {
      // Done processing
    } else {
      processRecursive()
    }
  }

  // Fiber to consume results from 'ready' and handle them
  Task {
    var count = 0
    while (_total == -1 || count < _total) {
      ready.blockingPoll() match {
        case Some(r) =>
          handle(r)
          count += 1
        case None =>
          // No result ready yet
          Thread.sleep(1) // Again, consider using proper synchronization
      }
    }
    complete(_total)
  }.start()
}