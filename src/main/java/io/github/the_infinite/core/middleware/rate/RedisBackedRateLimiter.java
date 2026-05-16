package io.github.the_infinite.core.middleware.rate;

import io.vertx.core.Future;
import io.vertx.redis.client.Redis;
import io.vertx.redis.client.RedisAPI;
import java.util.Arrays;

/**
 * A Redis-backed implementation of RateLimiter.
 */
public class RedisBackedRateLimiter implements RateLimiter {
    private final RedisAPI redis;

    public RedisBackedRateLimiter(Redis redis) {
        this.redis = RedisAPI.api(redis);
    }

    @Override
    public Future<Boolean> isAllowed(String key, int limit) {
        long currentMinute = System.currentTimeMillis() / 60000;
        String redisKey = "rate_limit:" + key + ":" + currentMinute;

        return redis.incr(redisKey).compose(response -> {
            long count = response.toLong();
            if (count == 1) {
                // First request in this minute, set expiration to 60 seconds
                // We map this to true as the first request is always allowed (assuming limit > 0)
                return redis.expire(Arrays.asList(redisKey, "60")).map(v -> true);
            }
            return Future.succeededFuture(count <= limit);
        });
    }
}
