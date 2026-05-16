package io.github.the_infinite.core.middleware;

import io.github.the_infinite.core.logging.correlation.CorrelationContext;
import io.github.the_infinite.core.middleware.rate.RateLimiter;
import io.github.the_infinite.core.response.ErrorResult;

import io.vertx.core.Handler;

/**
 * A collection of general-purpose middlewares for the framework.
 */
@SuppressWarnings("unused")
public final class GeneralMiddlewares {
  public static final String PAGINATION_LIMIT = "Pagination.Limit";
  public static final String PAGINATION_CURSOR = "Pagination.Cursor";

  /**
   * Extracts the existing correlation ID from the request header "X-Correlation-ID" if present.
   *
   * @return A handler that extracts the correlation ID.
   */
  public static Handler<CorrelationContext> correlationIdExtractor() {
    return ctx -> {
      final var correlationId = ctx.router().request().getHeader("X-Correlation-ID");
      if (correlationId != null && !correlationId.isEmpty()) {
        ctx.withCorrelationId(correlationId);
      }
      ctx.router().next();
    };
  }

  /**
   * A middleware that performs rate limiting using the provided RateLimiter.
   *
   * @param limiter The RateLimiter implementation to use.
   * @param limit   The maximum number of requests allowed per minute.
   * @return A handler that enforces rate limiting.
   */
  public static Handler<CorrelationContext> rateLimiter(RateLimiter limiter, int limit) {
    return ctx -> {
      final var request = ctx.router().request();
      final var ip = request.remoteAddress().host();

      limiter.isAllowed(ip, limit).onComplete(ar -> {
        if (ar.succeeded()) {
          if (ar.result()) {
            ctx.router().next();
          } else {
            ctx.router().fail(new ErrorResult("Too many requests", request.path(), 429));
          }
        } else {
          ctx.router().fail(ar.cause());
        }
      });
    };
  }

  /**
   * Extracts pagination parameters (limit and cursor) from the request query parameters.
   *
   * @return A handler that extracts pagination parameters and stores them in the context.
   */
  public static Handler<CorrelationContext> paginationParamsExtractor() {
    return ctx -> {
      final var request = ctx.router().request();
      final var limitStr = request.getParam("limit");
      final var cursor = request.getParam("cursor");

      int limit = 10;
      if (limitStr != null) {
        try {
          limit = Integer.parseInt(limitStr);
        } catch (NumberFormatException ignored) {
        }
      }

      ctx.set(PAGINATION_LIMIT, limit);
      ctx.set(PAGINATION_CURSOR, cursor);

      ctx.router().next();
    };
  }
}
