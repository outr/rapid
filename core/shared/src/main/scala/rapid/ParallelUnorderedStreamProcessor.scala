package rapid

import scala.annotation.tailrec

case class ParallelUnorderedStreamProcessor[T, R](stream: ParallelStream[T, R],
                                                  handle: R => Unit,
                                                  complete: Int => Unit) {
  private val iteratorTask: Task[Iterator[T]] = Stream.task(stream.stream)
  private val ready = Queue[R](stream.maxBuffer)
  private val processing = Queue[Task[Unit]](stream.maxThreads)
  @volatile var counter = -1

  // Processes the iterator feeding through processing and finally into ready
  iteratorTask.map { iterator =>
    var size = 0
    iterator.foreach { t =>
      var task: Task[Unit] = null
      task = stream.f(t).map { r =>
        ready.add(r)
        processing.remove(task)
      }
      processing.add(task)
      task.start()
      size += 1
    }
    counter = size
  }.start()

  // Processes through the ready queue feeding to handle and finally complete
  Task(handleNext(0)).start()

  @tailrec
  private def handleNext(counter: Int): Unit = {
    val next = ready.poll()
    if (this.counter == counter) {
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