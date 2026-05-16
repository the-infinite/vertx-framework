package io.github.the_infinite.core.middleware.rate;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import io.vertx.core.Future;
import io.vertx.core.Vertx;

/**
 * An in-memory implementation of RateLimiter.
 */
public class InMemoryRateLimiter implements RateLimiter {
  private final Map<String, RateLimitState> states = new ConcurrentHashMap<>();

  public InMemoryRateLimiter(Vertx vertx) {
    vertx.setPeriodic(60000, id -> cleanup());
  }

  @Override
  public Future<Boolean> isAllowed(String key, int limit) {
    long currentMinute = System.currentTimeMillis() / 60000;
    String bucketKey = key + ":" + currentMinute;

    RateLimitState state = states.computeIfAbsent(bucketKey, k -> new RateLimitState(System.currentTimeMillis() + 60000));
    int count = state.count.incrementAndGet();

    return Future.succeededFuture(count <= limit);
  }

  private void cleanup() {
    long now = System.currentTimeMillis();
    states.entrySet().removeIf(entry -> entry.getValue().expiry < now);
  }

  private static class RateLimitState {
    final AtomicInteger count = new AtomicInteger(0);
    final long expiry;

    RateLimitState(long expiry) {
      this.expiry = expiry;
    }
  }
}
