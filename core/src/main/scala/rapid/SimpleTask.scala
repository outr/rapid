package rapid

class SimpleTask[Return](val f: () => Return) extends AnyVal with Task[Return]
