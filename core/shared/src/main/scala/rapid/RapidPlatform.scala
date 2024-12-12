package rapid

trait RapidPlatform {
  def createFiber[Return](task: Task[Return]): Fiber[Return]
}
