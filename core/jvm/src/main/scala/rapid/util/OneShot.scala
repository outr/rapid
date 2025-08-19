package rapid.util

import java.util.concurrent.CountDownLatch

/**
 * Minimal, allocation-light one-shot completion.
 * Cheaper than CompletableFuture for fiber completion.
 */
final class OneShot[A] {
  @volatile private[this] var res: Either[Throwable, A] = null
  private[this] val latch = new CountDownLatch(1)

  def complete(value: A): Boolean = {
    if (res eq null) {
      res = Right(value)
      latch.countDown()
      true
    } else false
  }

  def fail(t: Throwable): Boolean = {
    if (res eq null) {
      res = Left(t)
      latch.countDown()
      true
    } else false
  }

  def get(): A = {
    latch.await()
    val r = res
    if (r.isRight) r.right.get
    else throw r.left.get
  }

  def isDone: Boolean = (res ne null)
}