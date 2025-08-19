package rapid.runtime

trait TimerWakeable {
  def __externalResumeFromTimer(cont: AnyRef): Unit
}