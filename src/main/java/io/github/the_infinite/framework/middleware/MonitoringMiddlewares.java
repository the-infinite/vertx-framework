package io.github.the_infinite.framework.middleware;

import io.github.the_infinite.framework.logging.correlation.CorrelationContext;
import io.github.the_infinite.framework.logging.monitor.LogEvent;
import io.github.the_infinite.framework.utils.DataHelpers;

import java.util.Map;
import java.util.function.Function;

import io.vertx.core.Handler;

@SuppressWarnings("unused")
public final class MonitoringMiddlewares {
  private static final String REQUEST_ID = "App.RequestID";

  public static Handler<CorrelationContext> requestLogger(Function<LogEvent<String>, Void> monitor) {
    return ctx -> {
      final var startTime = System.currentTimeMillis();
      final var request = ctx.router().request();
      if (!ctx.isAvailable()) {
        final var endTime = System.currentTimeMillis();
        monitor.apply(LogEvent.create(
          "Request context is not available.",
          REQUEST_ID,
          "unknown",
          Map.of(
            "requestId", "unknown",
            "address", request.remoteAddress().host(),
            "port", Integer.toString(request.remoteAddress().port()),
            "correlationId", ctx.correlationId(),
            "path", request.path(),
            "status", "unknown",
            "statusCode", "unknown",
            "method", request.method().name(),
            "latencyMs",
            Long.toString(endTime - startTime),
            "latency",
            DataHelpers.getStyledDuration(endTime - startTime)
          )
        ));


        //? Next, please.
        ctx.router().next();

        //? Skip this.
        return;
      }

      //? If this request doesn't have a request ID, generate one and store it in the context...
      if (ctx.get(REQUEST_ID) == null) {
        final var requestId = DataHelpers.createToken(System.currentTimeMillis(),
          Long.SIZE, 16, "", false);

        ctx.set(REQUEST_ID, requestId);
      }

      //? Get the response.
      final var requestId = ctx.<String>get(REQUEST_ID);
      final var response = ctx.router().response();

      //? Now, write this request ID to the response...
      if (!response.headWritten() && !response.closed()) {
        response.putHeader("X-Request-ID", requestId);
        response.putHeader("X-Correlation-ID", ctx.correlationId());
      }

      //? Await the next function to complete, and then log the response...
      ctx.router().addEndHandler(endResult -> {
        final var endTime = System.currentTimeMillis();
        monitor.apply(LogEvent.create(
          "Incoming request from client",
          REQUEST_ID,
          requestId,
          Map.of(
            "requestId", requestId,
            "address", request.remoteAddress().host(),
            "port", Integer.toString(request.remoteAddress().port()),
            "correlationId", ctx.correlationId(),
            "path", request.path(),
            "method", request.method().name(),
            "status", response.getStatusMessage(),
            "statusCode", Integer.toString(response.getStatusCode()),
            "latencyMs",
            Long.toString(endTime - startTime),
            "latency",
            DataHelpers.getStyledDuration(endTime - startTime)
          )
        ));
      });

      //? Next, please.
      ctx.router().next();
    };
  }
}
