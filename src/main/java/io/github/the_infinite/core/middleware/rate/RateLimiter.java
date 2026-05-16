package io.github.the_infinite.core.middleware.rate;

import io.vertx.core.Future;

/**
 * Interface for rate-limiting strategies.
 */
public interface RateLimiter {
    /**
     * Checks if a request is allowed under the given limit.
     *
     * @param key   The key to rate limit (e.g., IP address or user ID).
     * @param limit The maximum number of requests allowed per minute.
     * @return A future that resolves to true if the request is allowed, false otherwise.
     */
    Future<Boolean> isAllowed(String key, int limit);
}
