package io.github.the_infinite.framework.middleware;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

import io.github.the_infinite.framework.logging.correlation.CorrelationContext;
import io.github.the_infinite.framework.logging.correlation.CorrelationFlow;
import io.github.the_infinite.framework.middleware.rate.RateLimiter;
import io.github.the_infinite.framework.response.ErrorResult;
import io.github.the_infinite.framework.utils.DataHelpers;
import io.vertx.core.Handler;

/**
 * A collection of general-purpose middlewares for the framework.
 */
@SuppressWarnings("unused")
public final class GeneralMiddlewares {
  public static final String PAGINATION_LIMIT = "Pagination.Limit";
  public static final String PAGINATION_CURSOR = "Pagination.Cursor";

  public static Handler<CorrelationContext> requestIdentifier() {
    //? If this request doesn't have a request ID, generate one and store it in the context...
    return ctx -> {
      if (ctx.getRequestId() == null) {
        final var requestId = HexFormat.of().formatHex(new SecureRandom().generateSeed(Long.SIZE));
        ctx.setRequestId(requestId);
      }

      ctx.router().next();
    };
  }

  /**
   * Extracts the existing correlation ID from the request header "X-Correlation-ID" if present.
   *
   * @return A handler that extracts the correlation ID.
   */
  public static Handler<CorrelationContext> correlationIdExtractor() {
    return ctx -> {
      final var correlationId = ctx.router().request().getHeader("X-Correlation-ID");
      final var correlationFlow = Optional.ofNullable(
          ctx.router().request()
            .getHeader("X-Correlation-Flow")
        ).map(String::trim)
        .map(String::toUpperCase)
        .map(CorrelationFlow::valueOf)
        .orElse(CorrelationFlow.ONCE);
      if (correlationId != null && !correlationId.isEmpty()) {
        ctx.withCorrelationId(correlationId).withFlow(correlationFlow).router().next();
        return;
      }
      ctx.withFlow(correlationFlow).router().next();
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
      final var id = ctx.userId().orElse(request.remoteAddress().host());
      final var path = DataHelpers.toBase64(request.path());
      final var key = "%s:%s".formatted(id, path);

      limiter.isAllowed(key, limit).onSuccess(underLimit -> {
        //? If there is still room to make this request...
        if (underLimit) {
          ctx.router().next();
        }

        //? As there is no room...
        else {
          ctx.router().fail(new ErrorResult("Too many requests", request.path(), 429));
        }
      }).onFailure(err -> ctx.router().fail(Objects.requireNonNullElse(err.getCause(), err)));
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
      final var limitStr = request.getParam("limit", "15");
      final var cursor = request.getParam("cursor");

      int limit = 15;
      try {
        limit = Integer.parseInt(limitStr);
      } catch (NumberFormatException ignored) {
      }

      ctx.set(PAGINATION_LIMIT, limit);
      ctx.set(PAGINATION_CURSOR, cursor);
      ctx.router().next();
    };
  }
}
