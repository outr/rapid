package rapid

import java.util.concurrent.atomic.AtomicLong

/**
 * Thread-local fiber ID generator that eliminates CAS bottlenecks by using 
 * thread-local AtomicLong instances. Each thread gets its own counter space,
 * ensuring perfect distribution without contention.
 */
object FiberIdGenerator {
  private val threadLocalCounter = new ThreadLocal[AtomicLong] {
    override def initialValue(): AtomicLong = {
      // Start each thread's counter at a different offset based on thread ID
      // This helps with initial distribution across shards
      val threadHash = Thread.currentThread().threadId()
      new AtomicLong(threadHash << 32)
    }
  }
  
  /**
   * Generate a unique fiber ID. Thread-safe and contention-free.
   */
  def nextId(): Long = {
    threadLocalCounter.get().incrementAndGet()
  }
  
  /**
   * SplitMix64 hash function for uniform shard distribution.
   * This ensures that sequential IDs from the same thread are distributed
   * uniformly across timer wheel shards.
   */
  def hashForShard(id: Long): Int = {
    var z = id
    z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L
    z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL
    z = z ^ (z >>> 31)
    // Return positive int for modulo operations
    (z & 0x7fffffff).toInt
  }
  
  /**
   * Calculate which shard a fiber ID should be assigned to
   */
  def shardIndex(id: Long, numShards: Int): Int = {
    hashForShard(id) % numShards
  }
}