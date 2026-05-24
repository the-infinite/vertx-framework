package io.github.the_infinite.framework.data.cache;

public enum CachingStrategy {
  /**
   * Caching is not enabled.
   */
  DISABLED,

  /**
   * We would use an in-memory cache, which is faster but may not be suitable for
   * distributed applications or those with large datasets.
   */
  IN_MEMORY,

  /**
   * We would use Redis as the caching layer, which is a popular in-memory data structure
   * store for caching. It provides features like persistence, replication, and support for
   * various data structures, making it a good choice for distributed applications.
   */
  REDIS,
}
