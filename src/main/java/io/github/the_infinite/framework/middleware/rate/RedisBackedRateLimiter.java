package io.github.the_infinite.framework.middleware.rate;

import io.github.the_infinite.framework.data.cache.RedisStorage;
import io.vertx.core.Future;
import io.vertx.core.Vertx;

/**
 * A Redis-backed implementation of RateLimiter.
 */
class RedisBackedRateLimiter implements RateLimiter {
  private final RedisStorage redis;

  public RedisBackedRateLimiter(Vertx vertx) {
    this.redis = new RedisStorage(vertx);
  }

  @Override
  public Future<Boolean> isAllowed(String key, int limit) {
    long currentMinute = System.currentTimeMillis() / 60000;
    String redisKey = "rate_limit:" + key + ":" + currentMinute;

    return redis.incrementValue(redisKey).compose(response -> {
      long count = response;

      // First request in this minute, set expiration to 60 seconds
      // We map this to true as the first request is always allowed (assuming limit > 0)
      if (count == 1) {
        return redis.setExpiration(redisKey, 60).map(v -> true);
      }

      //? Now, we are okay.
      return Future.succeededFuture(count <= limit);
    });
  }
}
