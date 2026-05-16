package io.github.the_infinite.framework.logging.monitor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.the_infinite.framework.env.AppEnvironment;
import io.github.the_infinite.framework.logging.TimeEvent;
import io.github.the_infinite.framework.logging.correlation.CorrelationContext;
import io.github.the_infinite.framework.logging.correlation.ICorrelatedLogger;
import io.github.the_infinite.framework.monitoring.ILogMonitor;
import io.github.the_infinite.framework.monitoring.MonitoringEvent;
import io.github.the_infinite.framework.response.ErrorResult;
import io.github.the_infinite.framework.types.BatchContainer;
import io.github.the_infinite.framework.utils.DataHelpers;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.OffsetDateTime;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;

@SuppressWarnings("unused")
public class MonitorLogger implements ICorrelatedLogger<LogEvent<Object>> {
  private static final MonitorLogger.Options defaultOptions = new Options();
  private final Vertx vertx;
  private final ILogMonitor monitor;
  private final BatchContainer<MonitoringEvent> currentBatch;
  private MonitorLogger.Options options;

  private OffsetDateTime lastSent;

  private MonitorLogger(Vertx vertx, ILogMonitor monitor) {
    this.vertx = vertx;
    this.monitor = monitor;
    this.options = defaultOptions;
    this.lastSent = OffsetDateTime.now();
    this.currentBatch = new BatchContainer<>(options.batchSize);
  }

  public static String serialize(@NotNull Object object) {
    if (object instanceof String str) {
      return str;
    }

    try {
      return (new ObjectMapper()).writeValueAsString(object);
    } catch (JsonProcessingException e) {
      return object.toString();
    }
  }

  public static MonitorLogger create(Vertx scope, ILogMonitor monitor, @Nullable Options options) {
    final var logger = new MonitorLogger(scope, monitor);


    if (options != null) {
      logger.options = options;
    }

    return logger;
  }

  private Future<Void> sendEvent(MonitoringEvent event, int attemptsLeft) {
    final var promise = event.correlation().<Void>promise();

    monitor.publishEvent(event).andThen(publishResult -> {
      //? If this failed.
      if (publishResult.failed()) {
        //? If this should retry...
        if (options.shouldRetry && attemptsLeft > 0) {
          vertx.setTimer(options.retryInterval, timerId -> sendEvent(event, attemptsLeft - 1)
            .onSuccess(promise::succeed).onFailure(promise::fail));
        }

        //? It should just fail.
        else {
          promise.fail(publishResult.cause());
        }

        //? Okay then.
        return;
      }

      //? If this has a result...
      promise.succeed();
    });

    return promise.future();
  }

  private Future<Void> sendCurrentBatch(int attemptsLeft) {
    if (currentBatch.poll().isEmpty()) {
      return Future.failedFuture(new Exception("There are no events to push"));
    }

    final var promise = Promise.<Void>promise();
    final var eventsList = currentBatch.poll().get();

    //? Try publishing these events.
    monitor.publishEvents(eventsList).andThen(publishResult -> {
      //? If this failed.
      if (publishResult.failed()) {
        //? If this should retry...
        if (options.shouldRetry && attemptsLeft > 0) {
          vertx.setTimer(options.retryInterval, timerId -> sendCurrentBatch(attemptsLeft - 1)
            .onSuccess(promise::succeed).onFailure(promise::fail));
        }

        //? It should just fail.
        else {
          promise.fail(publishResult.cause());
        }

        //? Okay then.
        return;
      }

      //? If this has a result...
      promise.succeed();
    });

    //? Return this future.
    return promise.future();
  }

  private Future<Long> acquireLock(CorrelationContext context, int attemptsLeft) {
    final var promise = context.<Long>promise();

    //? Since we now have a provisional ID, do the necessary logging.
    monitor.getProvisionalId().andThen(idResult -> {
      //? If this failed.
      if (idResult.failed()) {
        //? If this should retry...
        if (options.shouldRetry && attemptsLeft > 0) {
          vertx.setTimer(options.retryInterval, timerId -> acquireLock(context, attemptsLeft - 1)
            .onSuccess(promise::succeed).onFailure(promise::fail));
        }

        //? It should just fail.
        else {
          promise.fail(idResult.cause());
        }

        //? Okay then.
        return;
      }

      //? This has then been locked.
      promise.succeed(idResult.result());
    });

    return promise.future();
  }

  private Future<Void> createEvent(MonitoringEvent.Level level,
                                   Long duration, CorrelationContext context,
                                   LogEvent<Object> messageData, int attemptsLeft) {
    final var promise = context.<Void>promise();
    final var timestamp = OffsetDateTime.now();

    //? Okay then.
    this.vertx.executeBlocking(() -> acquireLock(context, attemptsLeft).andThen(idResult -> {
      //? This is the ID of the event.
      var eventId = 0L;

      //? If we could not acquire the lock...
      if (!idResult.succeeded()) {
        promise.fail(idResult.cause());
        return;
      }

      eventId = idResult.result();
      final var message = messageData.message();
      final var data = messageData.data();

      //? Then push this event to our batching queue, or send it directly.
      final var event = new MonitoringEvent(eventId, timestamp.toInstant().toEpochMilli(), duration, context, messageData.group(), messageData.topic(), level, message, data);

      //? Since we have built the event...
      if (!options.shouldBatch) {
        //? Delegate this to sending.
        sendEvent(event, attemptsLeft).onSuccess(promise::succeed).onFailure(promise::fail);

        //? if this should not batch...
        return;
      }

      //? Since batching is enabled...
      currentBatch.add(event);

      //? If this is due for publishing...
      if (currentBatch.size() >= options.batchSize || timestamp.isAfter(lastSent.plusSeconds(options.batchInterval))) {
        lastSent = timestamp;
        this.sendCurrentBatch(attemptsLeft).onSuccess(promise::succeed).onFailure(promise::fail);
      }
    }));

    //? Return this future.
    return promise.future();
  }

  private Future<Void> createEvent(MonitoringEvent.Level level, CorrelationContext context, LogEvent<Object> messageData, int attemptsLeft) {
    return this.createEvent(level, null, context, messageData, attemptsLeft);
  }

  @Override
  public final Future<Void> exec(CorrelationContext context, LogEvent<Object> message) {
    return this.createEvent(MonitoringEvent.Level.EXEC, context, message, options.maxRetries);
  }

  @Override
  public final Future<Void> error(CorrelationContext context, LogEvent<Object> message) {
    return this.createEvent(MonitoringEvent.Level.ERROR, context, message, options.maxRetries);
  }

  @Override
  public final Future<Void> info(CorrelationContext context, LogEvent<Object> message) {
    return this.createEvent(MonitoringEvent.Level.INFO, context, message, options.maxRetries);
  }

  @Override
  public final Future<Void> debug(CorrelationContext context, LogEvent<Object> message) {
    final var env = AppEnvironment.getInstance();

    //? Never submit debug logs to production.
    if (env.getKind() == AppEnvironment.EnvironmentKind.PRODUCTION) {
      return Future.succeededFuture();
    }

    //? This is fine too.
    return this.createEvent(MonitoringEvent.Level.DEBUG, context, message, options.maxRetries);
  }

  @Override
  public final Future<Void> warn(CorrelationContext context, LogEvent<Object> message) {
    return this.createEvent(MonitoringEvent.Level.WARN, context, message, options.maxRetries);
  }

  @Override
  public final TimeEvent time(CorrelationContext context, LogEvent<Object> message) {
    return new TimeEvent((message.message()), (msg, runtime) -> this.createEvent(
      MonitoringEvent.Level.TIME,
      runtime.toMillis(),
      context,
      LogEvent.create("%s (Δ: %s)"
          .formatted(
            msg,
            DataHelpers.getStyledDuration(runtime.toMillis())
          ),
        message.group(),
        message.data()
      ),
      options.maxRetries
    ));
  }

  public static class Options {
    public int batchSize;
    public int maxRetries;
    public boolean shouldBatch;
    public long batchInterval;
    public long retryInterval;
    public boolean shouldRetry;
    public boolean useRetryQueue;
    public boolean useDeadLetterQueue;
    public OnFailed onFailed;

    public Options() {
      this.batchSize = 50;
      this.shouldBatch = false;
      this.batchInterval = 30;
      this.retryInterval = 30000;
      this.shouldRetry = true;
      this.useRetryQueue = false;
      this.useDeadLetterQueue = false;
      this.maxRetries = 5;
      this.onFailed = null;
    }

    /**
     * A function used to set exactly how many items should be batched inside of this
     * monitor logger before it sends events downstream to its monitor.
     */
    public Options setBatchSize(int batchSize) {
      assert (batchSize > 0);
      this.batchSize = batchSize;
      return this;
    }

    /**
     * Whether this should even batch at all. This takes precedence over the batch
     * size. Therefore, if this is set to false, it would never batch.
     */
    public Options setShouldBatch(boolean shouldBatch) {
      this.shouldBatch = shouldBatch;
      return this;
    }

    /**
     * The maximum time we expect between batches that any messages should be sent. So
     * that at least every interval seconds, we can push the current batch to the monitor
     * even if we have not yet met up with the batch size.
     */
    public Options setBatchInterval(long interval) {
      assert (interval > 0);
      this.batchInterval = interval;
      return this;
    }

    /**
     * The time we would want to wait before attempting to retry publishing to the monitor
     * subsequently.
     */
    public Options setRetryInterval(long interval) {
      assert (interval > 0);
      this.retryInterval = interval;
      return this;
    }

    /**
     * Whether this should use a retry queue for consumer failures.
     */
    public Options setUseRetryQueue(boolean useRetryQueue) {
      this.useRetryQueue = useRetryQueue;
      return this;
    }

    /**
     * Whether this should use a dead letter queue for consumer failures.
     */
    public Options setUseDeadLetterQueue(boolean useDeadLetterQueue) {
      this.useDeadLetterQueue = useDeadLetterQueue;
      return this;
    }

    /**
     * What to do when this logger fails to publish to the monitor.
     */
    public Options setOnFailed(@NotNull OnFailed handler) {
      this.onFailed = handler;
      return this;
    }

    public interface OnFailed {
      Future<Void> onFailed(ErrorResult reason);
    }
  }
}
