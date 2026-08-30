package io.github.the_infinite.framework.logging.monitor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Objects;

import io.github.the_infinite.framework.env.AppEnvironment;
import io.github.the_infinite.framework.logging.TimeEvent;
import io.github.the_infinite.framework.logging.correlation.CorrelationContext;
import io.github.the_infinite.framework.logging.correlation.ICorrelatedLogger;
import io.github.the_infinite.framework.monitoring.ILogMonitor;
import io.github.the_infinite.framework.monitoring.MonitoringEvent;
import io.github.the_infinite.framework.response.ErrorResult;
import io.github.the_infinite.framework.types.BatchContainer;
import io.github.the_infinite.framework.utils.DataHelpers;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;

/**
 * Correlated logger implementation that publishes framework log events through an {@link ILogMonitor}.
 * <p>
 * The logger can publish events immediately or buffer them into batches, depending on the configured
 * {@link Options}. Batched loggers also support periodic flushing, so partially filled batches do not remain
 * in memory indefinitely.
 */
@SuppressWarnings("unused")
public class MonitorLogger implements ICorrelatedLogger<LogEvent<Object>> {
  private static final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
  private final Vertx vertx;
  private final ILogMonitor monitor;
  private final BatchContainer<MonitoringEvent> currentBatch;
  private final MonitorLogger.Options options;
  private final long batchFlushTimerId;

  private MonitorLogger(Vertx vertx, ILogMonitor monitor, MonitorLogger.Options options) {
    this.vertx = Objects.requireNonNull(vertx, "vertx cannot be null");
    this.monitor = Objects.requireNonNull(monitor, "monitor cannot be null");
    this.options = options.copy().normalize();
    this.currentBatch = new BatchContainer<>(this.options.batchSize);
    this.batchFlushTimerId = this.options.shouldBatch
      ? this.vertx.setPeriodic(this.options.batchInterval * 1000, timerId -> flush().onFailure(this::handleFailure))
      : -1;
  }

  /**
   * Serializes the given value for transport or storage in monitoring payloads.
   * Strings are returned as-is; all other types are serialized as JSON when possible.
   */
  public static String serialize(@NotNull Object object) {
    if (object instanceof String str) {
      return str;
    }

    try {
      return mapper.writeValueAsString(object);
    } catch (JsonProcessingException e) {
      return object.toString();
    }
  }

  /**
   * Creates a logger with default options.
   */
  public static MonitorLogger create(Vertx scope, ILogMonitor monitor) {
    return create(scope, monitor, null);
  }

  /**
   * Creates a logger bound to the supplied Vert.x scope and monitor implementation.
   * The provided options are copied defensively so later external mutations do not affect this logger.
   */
  public static MonitorLogger create(Vertx scope, ILogMonitor monitor, @Nullable Options options) {
    return new MonitorLogger(scope, monitor, options == null ? new Options() : options);
  }

  /**
   * Returns an immutable snapshot of the configuration used by this logger instance.
   */
  public @NotNull Options getOptions() {
    return options.copy();
  }

  private void handleFailure(Throwable cause) {
    if (options.onFailed == null) {
      return;
    }

    options.onFailed.onFailed(ErrorResult.of(cause)).onFailure(ignored -> {
    });
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
          handleFailure(publishResult.cause());
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

  private Future<Void> publishBatch(Collection<MonitoringEvent> events, int attemptsLeft) {
    final var promise = Promise.<Void>promise();

    //? Try publishing these events.
    monitor.publishEvents(events).andThen(publishResult -> {
      //? If this failed.
      if (publishResult.failed()) {
        //? If this should retry...
        if (options.shouldRetry && attemptsLeft > 0) {
          vertx.setTimer(options.retryInterval, timerId -> publishBatch(events, attemptsLeft - 1)
            .onSuccess(promise::succeed).onFailure(promise::fail));
        }

        //? It should just fail.
        else {
          handleFailure(publishResult.cause());
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

  private Future<Void> sendPendingBatches(boolean includeActiveBatch, int attemptsLeft) {
    if (includeActiveBatch) {
      currentBatch.flush();
    }

    final var nextBatch = currentBatch.poll();
    if (nextBatch.isEmpty()) {
      return Future.succeededFuture();
    }

    return publishBatch(nextBatch.get(), attemptsLeft)
      .compose(v -> sendPendingBatches(false, attemptsLeft));
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

    acquireLock(context, attemptsLeft).andThen(idResult -> {
      if (idResult.failed()) {
        promise.fail(idResult.cause());
        return;
      }

      final var eventId = idResult.result();
      final var message = messageData.message();
      final var data = messageData.data();

      //? Then push this event to our batching queue or send it directly.
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

      //? If a full batch is now ready, send it immediately.
      if (currentBatch.hasReadyBatches()) {
        this.sendPendingBatches(false, attemptsLeft).onSuccess(promise::succeed).onFailure(promise::fail);
        return;
      }

      promise.succeed();
    });

    //? Return this future.
    return promise.future();
  }

  private Future<Void> createEvent(MonitoringEvent.Level level, CorrelationContext context, LogEvent<Object> messageData, int attemptsLeft) {
    return this.createEvent(level, 0L, context, messageData, attemptsLeft);
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

  /**
   * Flushes all currently buffered log events.
   * <p>
   * For non-batched loggers this delegates to the monitor's own flush operation.
   */
  public Future<Void> flush() {
    if (!options.shouldBatch) {
      return monitor.flushEvents().mapEmpty();
    }

    return sendPendingBatches(true, options.maxRetries);
  }

  /**
   * Cancels any periodic batch timer and flushes remaining buffered events.
   */
  public Future<Void> close() {
    if (batchFlushTimerId >= 0) {
      vertx.cancelTimer(batchFlushTimerId);
    }

    return flush();
  }

  /**
   * Configuration for {@link MonitorLogger} delivery, batching, and failure handling.
   */
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
    public int prefetchCount;
    public long processingTimeoutMs;

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
      this.prefetchCount = 16;
      this.processingTimeoutMs = 0;
    }

    private Options(Options other) {
      this.batchSize = other.batchSize;
      this.maxRetries = other.maxRetries;
      this.shouldBatch = other.shouldBatch;
      this.batchInterval = other.batchInterval;
      this.retryInterval = other.retryInterval;
      this.shouldRetry = other.shouldRetry;
      this.useRetryQueue = other.useRetryQueue;
      this.useDeadLetterQueue = other.useDeadLetterQueue;
      this.onFailed = other.onFailed;
      this.prefetchCount = other.prefetchCount;
      this.processingTimeoutMs = other.processingTimeoutMs;
    }

    public Options copy() {
      return new Options(this);
    }

    private Options normalize() {
      if (batchSize <= 0) {
        throw new IllegalArgumentException("batchSize must be greater than 0");
      }

      if (batchInterval <= 0) {
        throw new IllegalArgumentException("batchInterval must be greater than 0");
      }

      if (retryInterval <= 0) {
        throw new IllegalArgumentException("retryInterval must be greater than 0");
      }

      if (maxRetries < 0) {
        throw new IllegalArgumentException("maxRetries cannot be negative");
      }

      if (prefetchCount < 0) {
        throw new IllegalArgumentException("prefetchCount cannot be negative");
      }

      if (processingTimeoutMs < 0) {
        throw new IllegalArgumentException("processingTimeoutMs cannot be negative");
      }

      return this;
    }

    /**
     * A function used to set exactly how many items should be batched inside this
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
     * Whether failed to publish operations should be retried automatically.
     */
    public Options setShouldRetry(boolean shouldRetry) {
      this.shouldRetry = shouldRetry;
      return this;
    }

    /**
     * Sets the maximum number of retry attempts to perform before giving up.
     */
    public Options setMaxRetries(int maxRetries) {
      assert (maxRetries >= 0);
      this.maxRetries = maxRetries;
      return this;
    }

    /**
     * The maximum time we expect between batches that any messages should be sent. So
     * that at least every interval period, we can push the current batch to the monitor
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
    public Options setOnFailed(@Nullable OnFailed handler) {
      this.onFailed = handler;
      return this;
    }

    /**
     * Maximum number of unacknowledged messages a single consumer channel will pull at once.
     * A bounded value protects the consumer from being overwhelmed and keeps redelivery flowing.
     */
    public Options setPrefetchCount(int prefetchCount) {
      this.prefetchCount = prefetchCount;
      return this;
    }

    /**
     * If greater than zero, a message whose handler has not completed within this many milliseconds is
     * negatively acknowledged (requeued), so the slot is recycled. Consumers must be idempotent for this to be safe.
     */
    public Options setProcessingTimeout(long processingTimeoutMs) {
      this.processingTimeoutMs = processingTimeoutMs;
      return this;
    }

    public interface OnFailed {
      Future<Void> onFailed(ErrorResult reason);
    }
  }
}
