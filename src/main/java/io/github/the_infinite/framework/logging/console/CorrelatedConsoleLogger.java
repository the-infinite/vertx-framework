package io.github.the_infinite.framework.logging.console;

import io.github.the_infinite.framework.ConfigurationRegistrant;
import io.github.the_infinite.framework.env.AppEnvironment;
import io.github.the_infinite.framework.logging.TimeEvent;
import io.github.the_infinite.framework.logging.correlation.CorrelationContext;
import io.github.the_infinite.framework.logging.correlation.ICorrelatedLogger;
import io.github.the_infinite.framework.utils.DataHelpers;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.vertx.core.Future;
import io.vertx.core.Vertx;

@SuppressWarnings("unused")
public final class CorrelatedConsoleLogger implements ICorrelatedLogger<Object> {
  private static final Logger logger = LoggerFactory.getLogger(CorrelatedConsoleLogger.class);
  private static final Map<Vertx, CorrelatedConsoleLogger> instances = new ConcurrentHashMap<>();
  private final Vertx vertx;
  private final AppEnvironment env;

  private CorrelatedConsoleLogger(Vertx vertx) {
    this.vertx = vertx;
    this.env = AppEnvironment.getInstance();
    instances.put(vertx, this);
  }

  private static String executor(CorrelationContext context) {
    return "Cor.%s".formatted(context.correlationId());
  }

  public static CorrelatedConsoleLogger getInstance(@NotNull Vertx vertx) {
    if (!instances.containsKey(vertx)) {
      return new CorrelatedConsoleLogger(vertx);
    }
    return instances.get(vertx);
  }

  public static CorrelatedConsoleLogger getInstance() {
    return getInstance(ConfigurationRegistrant.vertx());
  }

  public static void initialize(Vertx vertx) {
    if (instances.containsKey(vertx)) {
      throw new IllegalStateException("Console is already initialized.");
    }

    new CorrelatedConsoleLogger(vertx);
  }

  @Override
  public Future<Void> exec(CorrelationContext context, Object message) {
    return vertx.executeBlocking(() -> {
      logger.atInfo()
        .addKeyValue("correlationId", context.correlationId())
        .addKeyValue("executor", executor(context))
        .log("{}", ConsoleLogger.message(message));
      return null;
    });
  }

  @Override
  public Future<Void> error(CorrelationContext context, Object message) {
    return vertx.executeBlocking(() -> {
      logger.atError()
        .addKeyValue("correlationId", context.correlationId())
        .addKeyValue("executor", executor(context))
        .log("{}", ConsoleLogger.message(message));
      return null;
    });
  }

  @Override
  public Future<Void> info(CorrelationContext context, Object message) {
    return vertx.executeBlocking(() -> {
      logger.atInfo()
        .addKeyValue("correlationId", context.correlationId())
        .addKeyValue("executor", executor(context))
        .log("{}", ConsoleLogger.message(message));
      return null;
    });
  }

  @Override
  public Future<Void> debug(CorrelationContext context, Object message) {
    //? Never submit debug logs to production.
    if (env.getKind() == AppEnvironment.EnvironmentKind.PRODUCTION) {
      return Future.succeededFuture();
    }

    //? Otherwise, log normally.
    return vertx.executeBlocking(() -> {
      logger.atDebug()
        .addKeyValue("correlationId", context.correlationId())
        .addKeyValue("executor", executor(context))
        .log("{}", ConsoleLogger.message(message));
      return null;
    });
  }

  @Override
  public Future<Void> warn(CorrelationContext context, Object message) {
    return vertx.executeBlocking(() -> {
      logger.atWarn()
        .addKeyValue("correlationId", context.correlationId())
        .addKeyValue("executor", executor(context))
        .log("{}", ConsoleLogger.message(message));
      return null;
    });
  }

  @Override
  public TimeEvent time(CorrelationContext context, Object message) {
    return new TimeEvent(ConsoleLogger.message(message), (msg, runtime) ->
      logger.atInfo()
        .addKeyValue("correlationId", context.correlationId())
        .addKeyValue("executor", executor(context))
        .addKeyValue("delta", runtime.toMillis())
        .log("{} (Δ: {})", msg, DataHelpers.getStyledDuration(runtime.toMillis())));
  }
}
