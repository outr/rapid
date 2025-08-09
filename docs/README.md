# Rapid
[![CI](https://github.com/outr/lightdb/actions/workflows/ci.yml/badge.svg)](https://github.com/outr/lightdb/actions/workflows/ci.yml)

**Rapid** is a high-performance, minimal-overhead effect, concurrency, and streaming library for Scala 2 and 3, designed
for scenarios where execution speed and allocation efficiency are critical. Primarily focused around simplicity and
convenience utilizing Virtual Threads for extremely fast performance.

It provides:
- A **`Task`** type for asynchronous or synchronous computations.
- A **`Fiber`** type for lightweight concurrency and cancellation.
- A **`Stream`** type for composable, lazy, potentially parallel processing of sequences.
- A set of **parallel combinators** (`par`, `parForeach`, `parFold`) for scaling workloads across threads.

## Inspiration

This project was born out of a deep curiosity about Virtual Threads and a desire to explore their potential performance compared to existing libraries.
What began as a mere benchmark swiftly transformed into a profound appreciation for the elegant simplicity and rapidity of this approach.
From there, the project's direction shifted towards building a library that prioritized practical execution models over purely effect-free semantics.
That’s why Rapid lets you run tasks in a straightforward, blocking, single-threaded way—or seamlessly kick them into multi-threaded parallel execution when performance demands it.

## Benchmarks

Take a look at the benchmarks to see how well it performs compared to the alternatives: https://github.com/outr/rapid/wiki/Benchmarks

## Features
- **Low overhead** — avoids unnecessary allocations, deep call stacks, and hidden costs.
- **Parallel-first** — easy to switch from sequential to parallel execution.
- **Deterministic performance** — predictable memory usage and execution patterns.
- **Benchmark-driven** — optimized with JMH benchmarks against Cats Effect, ZIO, and FS2.

---

## SBT Configuration

> Scala 2.13+ and Scala 3 are supported.

### Core
```scala
libraryDependencies += "com.outr" %% "rapid-core" % "@VERSION@"
```

### Scribe (Effects for Logging)
```scala
libraryDependencies += "com.outr" %% "rapid-scribe" % "@VERSION@"
```

### Test (Test features for running Task effects in ScalaTest)
```scala
libraryDependencies += "com.outr" %% "rapid-test" % "@VERSION@"
```

### Cats (Interoperability with Cats-Effect)
```scala
libraryDependencies += "com.outr" %% "rapid-cats" % "@VERSION@"
```

---

## Core Concepts

### `Task`
A `Task[A]` is a description of a computation that produces a value of type `A`.
It can be run synchronously with `.sync()` or executed in a `Fiber` for concurrency.

```scala mdoc
import rapid.Task
import scala.concurrent.duration._

val hello: Task[Unit] = Task {
  println("Hello, Rapid!")
}

val delayed: Task[String] =
  Task.sleep(500.millis).map(_ => "Done!")

hello.sync()
println(delayed.sync())
```

---

## `Fiber`
A `Fiber[A]` is a lightweight handle to a running `Task[A]`.
You can start tasks on fibers and wait for them to complete.

```scala mdoc
import rapid.Task

val fiber = Task {
  Thread.sleep(1000)
  "Completed!"
}.start()

println("Running in background...")
val result = fiber.sync()
println(result) // "Completed!"
```

---

## `Stream`
A `Stream[A]` is a lazy, composable sequence of `A` values backed by `Task`.
You can transform it sequentially or in parallel.

```scala mdoc
import rapid.{Stream, Task}

val s = Stream.emits(1 to 5)

val doubled = s.map(_ * 2).toList.sync()
// List(2, 4, 6, 8, 10)

val parallel = s.par(4)(i => Task(i * 2)).toList.sync()
// Parallel map with up to 4 threads
```

### Common Operations

```scala mdoc
val filtered = s.filter(_ % 2 == 0).toList.sync()     // List(2, 4)
val taken    = s.take(3).toList.sync()                // List(1, 2, 3)
val zipped   = s.zipWithIndex.toList.sync()           // List((1,0), (2,1), ...)
```

---

## Parallel Operators

### `.par`
Parallel map with a maximum number of threads.

```scala mdoc
Stream.emits(1 to 10)
  .par(maxThreads = 4)(i => Task(i * 2))
  .toList
  .sync()
```

### `.parForeach`
Fire-and-forget parallel processing for side-effects (no allocation of results).

```scala mdoc
import java.util.concurrent.atomic.AtomicLong
import rapid.Task

val sum = new AtomicLong(0)

Stream.emits(1 to 100)
  .parForeach(threads = 8) { i =>
    sum.addAndGet(i)
    Task.unit
  }
  .sync()

sum.get() // Sum of 1..100
```

### `.parFold`
Parallel reduction with per-thread accumulation and final merge.

```scala mdoc
val streamResult = Stream.emits(1 to 100)
  .parFold(0L, threads = 8)(
    (acc, i) => Task.pure(acc + i),
    _ + _
  )
  .sync()

streamResult // 5050
```

---

## Advanced: `ParallelStream`
`ParallelStream[T, R]` lets you control how elements are processed in parallel with a _forge_ function:
`T => Task[Option[R]]`. Results are ordered by input index and `None` values are dropped.

```scala mdoc
import rapid.{Stream, ParallelStream, Task}

val base = Stream.emits(1 to 10)
val ps   = ParallelStream(
  stream = base,
  forge  = (i: Int) => Task.pure(if (i % 2 == 0) Some(i * 10) else None),
  maxThreads = 8,
  maxBuffer  = 100000
)

val out = ps.toList.sync() // List(20, 40, 60, 80, 100)
```

You can `collect` after the forge to transform only kept values:

```scala mdoc
val doubledEvens = ps.collect { case x if x % 40 == 0 => x / 20 }.toList.sync()
// List(2, 4, 6, 8, 10) => filtered by collect and transformed
```

---

## Error Handling
`Task` failures raise exceptions. Use `attempt` to capture errors if you prefer explicit handling.

```scala mdoc
val t = Task {
  if (System.currentTimeMillis() % 2L == 0L) "ok"
  else throw new RuntimeException("boom")
}

t.attempt.sync() match {
  case scala.util.Success(v) => println(s"Success: $v")
  case scala.util.Failure(e) => println(s"Error: ${e.getMessage}")
}
```

---

## How It Works

### Execution Model
1. **`Task`** describes work.
2. **`Fiber`** runs work (typically on a JVM virtual thread).
3. **`Stream`** is a lazy pull that feeds elements through transforms.
4. **Parallel operators** batch/pipeline elements across multiple fibers and preserve ordering when appropriate.

### Performance Notes
- Hot loops use minimal allocations and mutable accumulators internally.
- Parallel pipelines are bounded by `maxThreads` and buffer sizes.
- `parForeach` avoids result allocation entirely for pure side-effect workloads.

---

## Benchmarks
Rapid includes JMH benchmarks comparing:
- `Task` overhead vs Cats Effect IO, and ZIO
- `Stream` processing vs FS2
- Sequential vs parallel throughput

Run:
```bash
sbt "jmh:run -i 3 -wi 3 -f1 -t1"
```