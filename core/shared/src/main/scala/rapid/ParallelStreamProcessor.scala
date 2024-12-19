package rapid

import java.util.concurrent.atomic.AtomicInteger
import scala.annotation.tailrec

case class ParallelStreamProcessor[T, R](stream: ParallelStream[T, R],
                                         handle: R => Unit,
                                         complete: Int => Unit) {
  private val iteratorTask: Task[Iterator[T]] = Stream.task(stream.stream)

  private val queue = new LockFreeQueue[(T, Int)](stream.maxBuffer)
  private val ready = new LockFreeQueue[R](stream.maxBuffer)
  @volatile private var _total = -1

  // Feed the iterator into the queue until empty
  iteratorTask.map { iterator =>
    var counter = 0
    iterator.zipWithIndex.foreach { tuple =>
      while (!queue.enqueue(tuple)) {
        Thread.`yield`()
      }
      counter += 1
    }
    _total = counter
  }.start()

  // Process the queue and feed into ready
  {
    val counter = new AtomicInteger(0)

    @tailrec
    def recurse(): Unit = {
      val next = queue.dequeue()
      next.foreach {
        case (t, index) =>
          val r = stream.f(t).sync()
          while (counter.get() != index) {
            Thread.`yield`()
          }
          while (!ready.enqueue(r)) {
            Thread.`yield`()
          }
          counter.incrementAndGet()
      }
      if (next.isEmpty && _total != -1) {
        // Finished
      } else {
        recurse()
      }
    }

    val tasks = (0 until stream.maxThreads).toList.map { _ =>
      Task(recurse())
    }
    TaskSeqOps(tasks).tasks
  }.start()

  def total: Option[Int] = if (_total == -1) None else Some(_total)

  // Processes through the ready queue feeding to handle and finally complete
  Task(handleNext(0)).start()

  @tailrec
  private def handleNext(counter: Int): Unit = {
    val next = ready.dequeue()
    if (_total == counter) {
      complete(counter)
    } else {
      val c = next match {
        case Opt.Value(value) =>
          handle(value)
          counter + 1
        case Opt.Empty => counter
      }
      handleNext(c)
    }
  }
}