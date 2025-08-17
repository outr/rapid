package rapid.monitor

import rapid.task.TaskCombinators._

import rapid.{Fiber, Task}

import javax.swing._
import java.awt._

class SwingTaskMonitor extends StatsTaskMonitor {
  private val frame = new JFrame("Task Monitor")
  private val statsArea = new JTextArea()

  statsArea.setForeground(Color.white)
  statsArea.setBackground(Color.black)
  statsArea.setFont(new Font("Arial", Font.BOLD, 18))
  statsArea.setEditable(false)
  statsArea.setLineWrap(true)
  statsArea.setWrapStyleWord(true)

  frame.setLayout(new BorderLayout())
  frame.add(new JScrollPane(statsArea, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER), BorderLayout.CENTER)
  frame.setSize(800, 600)
  frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE)
  frame.setVisible(true)

  // Method to update the stats in the JTextArea
  private def updateStats(): Unit = SwingUtilities.invokeLater(() => {
    statsArea.setText(report())
  })

  // Start a background task to refresh the UI periodically
  private def startUpdater(): Unit = {
    Task {
      while (true) {
        Thread.sleep(1000)
        updateStats()
      }
    }.start()
  }

  // Override methods to update stats and refresh the UI
  override def created[T](task: Task[T]): Unit = {
    super.created(task)
    updateStats()
  }

  override def fiberCreated[T](fiber: Fiber[T], from: Task[T]): Unit = {
    super.fiberCreated(fiber, from)
    updateStats()
  }

  override def start[T](task: Task[T]): Unit = {
    super.start(task)
    updateStats()
  }

  override def success[T](task: Task[T], result: T): Unit = {
    super.success(task, result)
    updateStats()
  }

  override def error[T](task: Task[T], throwable: Throwable): Unit = {
    super.error(task, throwable)
    updateStats()
  }

  // Start the updater thread
  startUpdater()
}

