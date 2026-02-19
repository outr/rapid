package spec

import rapid._

import scala.concurrent.duration._

class TaskJvmSpec extends AbstractBlockingTaskSpec {
  "Tasks (JVM-only)" should {
    "process a longer list of tasks with delays in parallel" in {
      val size = 5000
      val parallelism = 256
      (0 until size).map(i => Task.sleep(50.millis).map(_ => i.toLong * 2)).tasksParBounded(parallelism).map { list =>
        list.sum should be(size.toLong * (size.toLong - 1L))
      }.sync()
    }
  }
}
