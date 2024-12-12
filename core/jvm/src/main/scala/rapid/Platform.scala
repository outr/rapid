package rapid

object Platform extends RapidPlatform {
//  override def createFiber[Return](task: Task[Return]): Fiber[Return] = new VirtualThreadFiber[Return](task)
  override def createFiber[Return](task: Task[Return]): Fiber[Return] = new FutureFiber[Return](task)
}
