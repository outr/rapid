package rapid.monitor

import rapid.Task

/**
 * Monitor configuration - always disabled for zero overhead
 */
object MonitorConfig {
  
  // Always disabled - no monitoring overhead
  def init(): Unit = {
    Task.monitor = null
  }
  
  // Auto-initialize on class load
  init()
}

// Ensure this gets loaded when Task is used
private[rapid] object MonitorAutoLoader {
  def ensureLoaded(): Unit = {
    MonitorConfig.init()
  }
}