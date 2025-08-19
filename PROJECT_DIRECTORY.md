# Rapid - Project Directory Structure

**Generated**: 2025-08-18  
**Purpose**: Complete map of the Rapid Scala concurrency library  
**Type**: Scala/SBT multi-module library project

## Overview

Rapid is a high-performance, minimal-overhead effect, concurrency, and streaming library for Scala 2 and 3, designed for scenarios where execution speed and allocation efficiency are critical. Built around Virtual Threads for extremely fast performance.

## Root Structure

```
rapid/
├── LICENSE                     # MIT License
├── README.md                   # Main project documentation
├── build.sbt                   # SBT build configuration
├── publish.sh                  # Publishing script
├── project/                    # SBT project configuration
│   ├── build.properties       # SBT version
│   └── plugins.sbt            # SBT plugins
├── docs/                      # Documentation
│   └── README.md             # Documentation source (mdoc)
├── benchmark/                 # JMH benchmarks
├── core/                     # Core library module
├── scribe/                   # Scribe logging integration
├── cats/                     # Cats Effect interoperability
└── test/                     # Testing utilities
```

## Core Module Structure

```
core/
├── js/                       # Scala.js platform
│   └── src/main/scala/rapid/
│       ├── Platform.scala    # JS-specific platform code
│       └── package.scala     # Platform package
├── jvm/                      # JVM platform
│   └── src/
│       ├── main/scala/rapid/
│       │   ├── FixedThreadPoolFiber.scala  # Thread pool fiber
│       │   ├── Platform.scala              # JVM-specific platform
│       │   ├── VirtualThreadFiber.scala    # Virtual thread fiber
│       │   └── package.scala               # Platform package
│       └── test/scala/spec/   # JVM-specific tests
│           ├── BasicsSpec.scala
│           ├── FiberWaitingSpec.scala
│           └── ParallelStreamSpec.scala
├── native/                   # Scala Native platform
│   └── src/main/scala/rapid/
│       ├── Platform.scala    # Native-specific platform
│       └── package.scala     # Platform package
└── shared/                   # Cross-platform code
    └── src/
        ├── main/scala/rapid/ # Core library implementation
        │   ├── AtomicIndexedQueue.scala      # Concurrent queue
        │   ├── Blockable.scala               # Blocking operations
        │   ├── BoundedMPMCQueue.scala        # Multi-producer/consumer queue
        │   ├── ConcurrentQueue.scala         # Concurrent queue interface
        │   ├── Cursor.scala                  # Stream cursor
        │   ├── Fiber.scala                   # Lightweight concurrency
        │   ├── Forge.scala                   # Task composition
        │   ├── FutureFiber.scala            # Future-based fiber
        │   ├── Grouped.scala                 # Grouped operations
        │   ├── GroupedIterator.scala         # Iterator for groups
        │   ├── GroupingIterator.scala        # Grouping iterator
        │   ├── Holder.scala                  # Value holder
        │   ├── ParallelStream.scala          # Parallel stream processing
        │   ├── ParallelStreamProcessor.scala # Stream processor
        │   ├── Pull.scala                    # Pull-based streams
        │   ├── RapidApp.scala               # Application base
        │   ├── RapidPackage.scala           # Package utilities
        │   ├── RapidPlatform.scala          # Platform abstraction
        │   ├── Repeat.scala                  # Repeat operations
        │   ├── SingleThreadAgent.scala      # Single-thread agent
        │   ├── Step.scala                    # Processing step
        │   ├── Stream.scala                  # Core stream type
        │   ├── Task.scala                    # Core task type
        │   ├── Timer.scala                   # Timer utilities
        │   ├── Unique.scala                  # Unique identifiers
        │   ├── UniqueDefaults.scala         # Default unique values
        │   ├── monitor/                     # Task monitoring
        │   │   ├── StatsTaskMonitor.scala   # Statistics monitor
        │   │   ├── SwingTaskMonitor.scala   # Swing GUI monitor
        │   │   └── TaskMonitor.scala        # Monitor interface
        │   ├── ops/                         # Operation extensions
        │   │   ├── ByteStreamOps.scala      # Byte stream operations
        │   │   ├── CharStreamOps.scala      # Character stream operations
        │   │   ├── FiberSeqOps.scala        # Fiber sequence operations
        │   │   ├── OptionParallelStreamOps.scala # Option parallel ops
        │   │   ├── OptionStreamOps.scala    # Option stream operations
        │   │   ├── TaskSeqOps.scala         # Task sequence operations
        │   │   └── TaskTaskOps.scala        # Task-to-task operations
        │   └── task/                        # Task implementations
        │       ├── CompletableTask.scala    # Completable task
        │       ├── ErrorTask.scala          # Error task
        │       ├── FlatMapTask.scala        # FlatMap task
        │       ├── PureTask.scala           # Pure value task
        │       ├── SingleTask.scala         # Single operation task
        │       ├── SleepTask.scala          # Sleep/delay task
        │       ├── Taskable.scala           # Taskable interface
        │       └── UnitTask.scala           # Unit task
        └── test/scala/spec/    # Shared tests
            ├── BasicsSyncSpec.scala     # Basic synchronous tests
            ├── FiberSpec.scala          # Fiber tests
            ├── MaterializedCursorSpec.scala # Cursor tests
            ├── StreamSpec.scala         # Stream tests
            └── TaskSpec.scala           # Task tests
```

## Benchmark Module Structure

```
benchmark/
├── results/                  # Benchmark results
│   ├── benchmarks-0.1.0.json
│   ├── benchmarks-0.11.0.json
│   ├── benchmarks-0.13.0.json
│   ├── benchmarks-0.18.0.json
│   ├── benchmarks-0.3.0.json
│   ├── benchmarks-1.0.0.json
│   └── benchmarks.2024.12.05.json
└── src/main/scala/benchmark/  # JMH benchmarks
    ├── ManySleepsBenchmark.scala    # Sleep operation benchmarks
    ├── ManyTasksBenchmark.scala     # Task creation benchmarks
    ├── OverheadBenchmark.scala      # Overhead comparison
    ├── ParallelTesting.scala        # Parallel execution tests
    └── StreamBenchmark.scala        # Stream processing benchmarks
```

## Additional Modules

### Scribe Module (Logging Integration)
```
scribe/
└── shared/src/
    ├── main/scala/rapid/
    │   ├── RapidLoggerSupport.scala  # Logger support
    │   ├── RapidLoggerWrapper.scala  # Logger wrapper
    │   └── logger.scala              # Logger utilities
    └── test/scala/spec/
        └── ScribeRapidSpec.scala     # Scribe integration tests
```

### Cats Module (Cats Effect Interoperability)
```
cats/
├── jvm/src/test/scala/spec/
│   └── ExtrasSpec.scala       # JVM-specific extras tests
└── shared/src/main/scala/rapid/cats/
    └── package.scala          # Cats Effect interop
```

### Test Module (Testing Utilities)
```
test/
├── jvm/src/test/scala/spec/
│   └── BlockableSpec.scala    # JVM-specific blockable tests
└── shared/src/
    ├── main/scala/rapid/
    │   └── AsyncTaskSpec.scala # Async task testing utilities
    └── test/scala/spec/
        └── BasicsAsyncSpec.scala # Basic async tests
```

## Key File Analysis

### Core Library Files
- **Task.scala**: Core task type for asynchronous/synchronous computations
- **Fiber.scala**: Lightweight concurrency and cancellation
- **Stream.scala**: Composable, lazy, potentially parallel processing
- **ParallelStream.scala**: Advanced parallel stream processing with forge functions

### Platform Abstraction
- **Platform.scala** (per platform): Platform-specific implementations
- **VirtualThreadFiber.scala**: JVM Virtual Thread implementation
- **FixedThreadPoolFiber.scala**: Traditional thread pool implementation

### Performance-Critical Components
- **AtomicIndexedQueue.scala**: Lock-free concurrent queue
- **BoundedMPMCQueue.scala**: Multi-producer/multi-consumer queue
- **ParallelStreamProcessor.scala**: Parallel processing engine

### Build Configuration
- **build.sbt**: Multi-module SBT configuration supporting Scala 2.13+ and 3.x
- **project/plugins.sbt**: JMH plugin for benchmarking, mdoc for documentation

## Technology Stack

### Languages & Platforms
- **Scala**: 2.13.16, 3.3.6
- **Platforms**: JVM, Scala.js, Scala Native (partially supported)
- **Build Tool**: SBT with cross-platform compilation

### Key Dependencies
- **Testing**: ScalaTest 3.2.19
- **Logging**: Scribe 3.17.0
- **Interop**: Cats Effect 3.6.3, FS2 3.12.0
- **Benchmarking**: JMH 1.37
- **Documentation**: mdoc

### Performance Focus
- **Virtual Threads**: Primary concurrency model on JVM
- **Zero-allocation**: Hot paths avoid allocations
- **Bounded resources**: Configurable thread and buffer limits
- **JMH benchmarking**: Continuous performance validation

## Development Workflow

### Testing
```bash
sbt test                    # Run all tests
sbt "project core" test     # Test specific module
```

### Benchmarking
```bash
sbt "jmh:run -i 3 -wi 3 -f1 -t1"  # Run JMH benchmarks
```

### Documentation
```bash
sbt docs/mdoc              # Generate documentation
```

### Publishing
```bash
./publish.sh               # Publish to Sonatype Central
```

## Architecture Patterns

### Effect System Design
- **Task**: Computation description (lazy evaluation)
- **Fiber**: Computation execution (eager evaluation)
- **Stream**: Lazy sequence processing with parallel capabilities

### Concurrency Model
- **Virtual Threads**: Primary execution model for lightweight concurrency
- **Thread Pools**: Fallback for platforms without virtual thread support
- **Lock-free Queues**: High-performance concurrent data structures

### Performance Optimization
- **Allocation avoidance**: Mutable accumulators in hot paths
- **Pipeline batching**: Efficient parallel processing
- **Resource bounding**: Configurable limits prevent resource exhaustion

---

*This project represents a modern, performance-focused approach to effect systems in Scala, prioritizing practical execution models and measurable performance over purely functional semantics.*