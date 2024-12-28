package rapid

case class Grouped[G, T](group: G, results: List[T])
