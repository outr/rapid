package rapid

case class ParallelStream[T, R](stream: Stream[T],
                                f: T => Task[R],
                                maxThreads: Int = ParallelStream.DefaultMaxThreads,
                                maxBuffer: Int = ParallelStream.DefaultMaxBuffer) {
}

object ParallelStream {
  val DefaultMaxThreads: Int = Runtime.getRuntime.availableProcessors * 2
  val DefaultMaxBuffer: Int = 1_000
}