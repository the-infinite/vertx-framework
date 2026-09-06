package io.github.the_infinite.framework.logging.console;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.the_infinite.framework.ConfigurationRegistrant;
import io.github.the_infinite.framework.env.AppEnvironment;
import io.github.the_infinite.framework.logging.ILogger;
import io.github.the_infinite.framework.logging.TimeEvent;
import io.github.the_infinite.framework.utils.DataHelpers;
import io.vertx.core.Future;
import io.vertx.core.Vertx;

public final class ConsoleLogger implements ILogger {
  private static final Logger logger = LoggerFactory.getLogger(ConsoleLogger.class);
  private static final Map<Vertx, ConsoleLogger> instances = new ConcurrentHashMap<>();
  private final Vertx vertx;

  private ConsoleLogger(Vertx vertxInstance) {
    if (instances.containsKey(vertxInstance)) {
      throw new IllegalStateException("Console is already initialized.");
    }

    this.vertx = vertxInstance;
    instances.put(vertxInstance, this);
  }

  public static ConsoleLogger getInstance(Vertx vertx) {
    if (!instances.containsKey(vertx)) {
      throw new IllegalStateException("Console has not been initialized for this Vertx context.");
    }
    return instances.get(vertx);
  }

  public static ConsoleLogger getInstance() {
    return getInstance(ConfigurationRegistrant.vertx());
  }

  public static void initialize(Vertx vertx) {
    new ConsoleLogger(vertx);
  }

  static String message(Object message) {
    if (message instanceof String str) {
      return str;
    }

    if (message.getClass().isEnum()) {
      return message.getClass().getSimpleName();
    }

    //? Just return the value but to string.
    return message.toString();
  }

  static String executor() {
    final var config = ConfigurationRegistrant.getInstance();
    final var id = config.getContext().deploymentID();

    if (id != null && (config.getContext().isWorkerContext() || !("1").equals(id))) {
      return "Worker.%s".formatted(id);
    }

    return "Primary.Core";
  }

  @Override
  public Future<Void> debug(Object message) {
    final var env = AppEnvironment.getInstance();

    //? Never submit debug logs to production.
    if (env.getKind() == AppEnvironment.EnvironmentKind.PRODUCTION) {
      return Future.succeededFuture();
    }

    //? Otherwise, log normally.
    return vertx.executeBlocking(() -> {
      logger.atDebug()
        .addKeyValue("executor", executor())
        .log("{}", ConsoleLogger.message(message));
      return null;
    });
  }

  @Override
  public Future<Void> exec(Object message) {
    return vertx.executeBlocking(() -> {
      logger.atInfo()
        .addKeyValue("executor", executor())
        .log("{}", ConsoleLogger.message(message));
      return null;
    });
  }

  @Override
  public Future<Void> error(Object message) {
    return vertx.executeBlocking(() -> {
      logger.atError()
        .addKeyValue("executor", executor())
        .log("{}", ConsoleLogger.message(message));
      return null;
    });
  }

  @Override
  public Future<Void> info(Object message) {
    return vertx.executeBlocking(() -> {
      logger.atInfo()
        .addKeyValue("executor", executor())
        .log("{}", ConsoleLogger.message(message));
      return null;
    });
  }

  @Override
  public Future<Void> warn(Object message) {
    return vertx.executeBlocking(() -> {
      logger.atWarn()
        .addKeyValue("executor", executor())
        .log("{}", ConsoleLogger.message(message));
      return null;
    });
  }

  @Override
  public TimeEvent time(Object message) {
    return new TimeEvent(ConsoleLogger.message(message), (msg, runtime) ->
      logger.atInfo()
        .addKeyValue("executor", executor())
        .addKeyValue("delta", runtime.toMillis())
        .log("{} (Δ: {})", msg, DataHelpers.getStyledDuration(runtime.toMillis())));
  }
}
