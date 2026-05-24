package io.github.the_infinite.framework.middleware.rate;

import io.vertx.core.Vertx;

/**
 * Factory class for creating {@link RateLimiter} instances based on the configured type.
 * Uses the strategy pattern to allow for easy extension and replacement.
 */
@SuppressWarnings("unused")
public final class LimiterFactory {
  private static Type DEFAULT_TYPE = Type.REDIS;

  public static void setPreference(Type type) {
    DEFAULT_TYPE = type;
  }

  public static RateLimiter create(Vertx vertx) {
    if (DEFAULT_TYPE == Type.IN_MEMORY) {
      return new InMemoryRateLimiter(vertx);
    }

    return new RedisBackedRateLimiter(vertx);
  }

  public enum Type {
    REDIS,
    IN_MEMORY,
  }
}
