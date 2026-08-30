package io.github.the_infinite.framework.queue;

import com.rabbitmq.client.AMQP;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import io.github.the_infinite.framework.data.DatabaseFactory;
import io.github.the_infinite.framework.data.types.QueueConsumerHandler;
import io.github.the_infinite.framework.env.AppEnvironment;
import io.github.the_infinite.framework.logging.correlation.CorrelationContext;
import io.github.the_infinite.framework.logging.monitor.LogEvent;
import io.github.the_infinite.framework.logging.monitor.MonitorLogger;
import io.github.the_infinite.framework.monitoring.ILogMonitor;
import io.github.the_infinite.framework.utils.DataHelpers;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.rabbitmq.RabbitMQClient;
import io.vertx.rabbitmq.RabbitMQConsumer;
import io.vertx.rabbitmq.RabbitMQMessage;
import lombok.Getter;

@SuppressWarnings({"unused", "CallToPrintStackTrace"})
public class QueueConsumer<T, ResultType> {
  private final Vertx vertx;
  private final RabbitMQClient client;
  /**
   *  Gets the queue name for this consumer.
   */
  @Getter
  private final String queueName;
  /**
   *  Gets the consumer tag for this consumer.
   */
  @Getter
  private final String consumerTag;
  private final QueueConsumerHandler<T, ResultType> handler;
  private final Function<String, T> deserializer;
  private final CorrelationContext context;
  private final MonitorLogger logger;
  private final MonitorLogger.Options options;
  /**
   *  Gets the QueueManagement utility for managing queues, exchanges, and bindings.
   */
  @Getter
  private final QueueManagement queueManagement;
  private final Map<Long, Throwable> failErrors = new ConcurrentHashMap<>();
  private final Map<Long, ResultType> successResults = new ConcurrentHashMap<>();
  private RabbitMQConsumer consumer;

  public QueueConsumer(
    Vertx vertx, String queueName, ILogMonitor monitor,
    MonitorLogger.Options options, Function<String, T> deserializer,
    QueueConsumerHandler<T, ResultType> handler
  ) {
    this.vertx = vertx;
    this.context = CorrelationContext.from(vertx.getOrCreateContext());
    this.client = DatabaseFactory.getQueueClient(vertx);
    this.queueManagement = new QueueManagement(this.client);
    this.consumerTag = DataHelpers.createToken();
    this.options = options;
    this.logger = MonitorLogger.create(vertx, monitor, options);
    this.queueName = queueName;
    this.deserializer = deserializer;
    this.handler = handler;
    this.consumer = null;
  }

  public QueueConsumer(Vertx vertx, String queueName, ILogMonitor monitor,
                       Function<String, T> deserializer, QueueConsumerHandler<T, ResultType> handler) {
    this(vertx, queueName, monitor, new MonitorLogger.Options().setShouldBatch(false), deserializer, handler);
  }

  public final Future<Void> start() {
    if (client.isConnected()) {
      return Future.succeededFuture();
    }

    return client.start();
  }

  public final Future<Void> getStatistics() {
    final var promise = Promise.<Void>promise();
    queueManagement.getQueueInfo(queueName)
      .onFailure(promise::fail).onSuccess(result -> {
        try {
          final var declareOk = (AMQP.Queue.DeclareOk) result;
          logger.exec(context, LogEvent.create("Periodic queue statistics",
            getClass().getSimpleName(), Map.of(
              "queueName", queueName,
              "consumerCount", declareOk.getConsumerCount(),
              "messageCount", declareOk.getMessageCount(),
              "successCount", successResults.size(),
              "failCount", failErrors.size()
            )));
        } catch (ClassCastException ignored) {
          // Handle if result format changes
        }
        promise.succeed();
      });
    return promise.future();
  }

  public final Future<Void> consume() {
    if (consumer != null) {
      return Future.succeededFuture();
    }

    final var promise = Promise.<Void>promise();

    queueManagement.declareQueue(queueName, true, false, false)
      .compose(v -> {
        if (options.useRetryQueue) {
          return queueManagement.declareQueue(queueName + ".retry", true, false, false, new JsonObject()
            .put("x-dead-letter-exchange", "")
            .put("x-dead-letter-routing-key", queueName)
            .put("x-message-ttl", options.retryInterval));
        }
        return Future.succeededFuture();
      })
      .compose(v -> {
        if (options.useDeadLetterQueue) {
          return queueManagement.declareQueue(queueName + ".dlq", true, false, false);
        }
        return Future.succeededFuture();
      })
      .compose(v -> queueManagement.setPrefetch(options.prefetchCount))
      .compose(v -> queueManagement.createConsumer(queueName, consumerTag))
      .onFailure(promise::fail)
      .onSuccess(rabbitConsumer -> {
        consumer = rabbitConsumer;

        //? Surface transport-level failures (channel/connection drops) instead of silently swallowing them.
        consumer.exceptionHandler(error -> logger.error(context, LogEvent.create(
          "Consumer transport error", getClass().getName(), Map.of(
            "queueName", queueName,
            "consumerTag", consumerTag,
            "error", error.getMessage(),
            "stackTrace", Arrays.stream(error.getStackTrace()).map(StackTraceElement::toString)
          ))));

        this.logger.exec(context, LogEvent.create("A new consumer has been connected", getClass().getName(), Map.of(
          "queueName", queueName,
          "tag", consumerTag
        )));

        //? Add periodic tracking of progress
        if (AppEnvironment.getInstance().getKind() != AppEnvironment.EnvironmentKind.PRODUCTION) {
          vertx.setPeriodic(600000, id -> getStatistics()
            .onFailure(cause -> logger.error(context, LogEvent.create("Failed to get queue statistics", getClass().getSimpleName(), Map.of(
              "queueName", queueName,
              "error", cause.getMessage(),
              "stackTrace", Arrays.stream(cause.getStackTrace()).map(StackTraceElement::toString)
            )))));
        }

        //? Handle messages here.
        consumer.handler(this::handleMessage);

        //? This is done.
        promise.succeed();
      });

    return promise.future();
  }

  private void handleMessage(RabbitMQMessage message) {
    final long deliveryTag = message.envelope().getDeliveryTag();
    final var redelivered = message.envelope().isRedeliver();

    //? Exactly-once acknowledgement: whichever code path resolves the message first wins, the rest are ignored.
    final var settled = new AtomicBoolean(false);
    final long[] watchdog = {-1};

    final Runnable cancelWatchdog = () -> {
      if (watchdog[0] >= 0) {
        vertx.cancelTimer(watchdog[0]);
        watchdog[0] = -1;
      }
    };

    //? Optional safety net: if a handler never completes, recycle the message instead of pinning a prefetch slot.
    if (options.processingTimeoutMs > 0) {
      watchdog[0] = vertx.setTimer(options.processingTimeoutMs, tid -> {
        if (settled.compareAndSet(false, true)) {
          logger.error(context, LogEvent.create("Message processing timed out", getClass().getSimpleName(), Map.of(
            "queueName", queueName,
            "consumerTag", consumerTag,
            "messageId", deliveryTag,
            "timeoutMs", options.processingTimeoutMs
          )));
          //? Requeue so a (possibly stuck) in-flight attempt is superseded; consumers must be idempotent.
          queueManagement.rejectMessage(deliveryTag, false, true);
        }
      });
    }

    final Function<Boolean, Future<Void>> ack = multiple -> {
      cancelWatchdog.run();
      if (settled.compareAndSet(false, true)) {
        return queueManagement.acknowledgeMessage(deliveryTag, Boolean.TRUE.equals(multiple))
          .onFailure(err -> logger.error(context, LogEvent.create("Failed to ack message", getClass().getSimpleName(), Map.of(
            "queueName", queueName, "messageId", deliveryTag, "error", err.getMessage()
          ))));
      }
      return Future.succeededFuture();
    };

    final Function<Boolean, Future<Void>> nack = requeue -> {
      cancelWatchdog.run();
      if (settled.compareAndSet(false, true)) {
        //? An explicit manual nack with requeue=true short-circuits the retry/DLQ routing.
        if (Boolean.TRUE.equals(requeue)) {
          return queueManagement.rejectMessage(deliveryTag, false, true);
        }
        return routeAndSettle(deliveryTag, message, retryCountOf(message));
      }
      return Future.succeededFuture();
    };

    try {
      final var jsonObject = message.body().toJsonObject();
      final var messageData = deserializer.apply(jsonObject.encode());

      final var messageType = messageData.getClass();
      final var timeStart = logger.time(context, LogEvent.create("Message handled by consumer", getClass().getName(), Map.of(
        "queue", queueName,
        "consumerTag", consumerTag,
        "messageType", messageType.getName(),
        "startedAt", System.currentTimeMillis(),
        "messageId", deliveryTag
      )));

      handler.handle(messageData, redelivered, ack, nack).onComplete(handleResult -> {
        cancelWatchdog.run();
        try {
          if (handleResult.succeeded()) {
            successResults.put(deliveryTag, handleResult.result().getData());
            logger.exec(context, LogEvent.create("Message processed successfully", getClass().getSimpleName(), Map.of(
              "queue", queueName,
              "consumerTag", consumerTag,
              "messageType", messageType.getName(),
              "messageId", deliveryTag,
              "timestamp", System.currentTimeMillis(),
              "input", jsonObject.getMap(),
              "result", handleResult.result().serialize()
            )));
            ack.apply(false);
          } else {
            failErrors.put(deliveryTag, handleResult.cause());
            logger.error(context, LogEvent.create("Failed to process message", getClass().getSimpleName(), Map.of(
              "queue", queueName,
              "input", jsonObject.getMap(),
              "consumerTag", consumerTag,
              "messageType", messageType.getName(),
              "messageId", deliveryTag,
              "timestamp", System.currentTimeMillis(),
              "error", handleResult.cause().getMessage(),
              "stackTrace", Arrays.stream(handleResult.cause().getStackTrace()).map(StackTraceElement::toString)
            )));

            if (AppEnvironment.getInstance().getKind() != AppEnvironment.EnvironmentKind.PRODUCTION) {
              handleResult.cause().printStackTrace();
            }

            nack.apply(false);
          }
        } finally {
          timeStart.end();
        }
      });
    } catch (Exception ex) {
      cancelWatchdog.run();
      logger.error(context, LogEvent.create("Failed to handle message", getClass().getSimpleName(), Map.of(
        "queue", queueName,
        "consumerTag", consumerTag,
        "error", ex.getMessage(),
        "stackTrace", Arrays.stream(ex.getStackTrace()).map(StackTraceElement::toString)
      )));

      if (AppEnvironment.getInstance().getKind() != AppEnvironment.EnvironmentKind.PRODUCTION) {
        ex.printStackTrace();
      }

      //? A poison message (unparseable payload) should not be endlessly redelivered; route it to the DLQ or drop it.
      if (settled.compareAndSet(false, true)) {
        routeAndSettle(deliveryTag, message, retryCountOf(message)).onFailure(v ->
          queueManagement.rejectMessage(deliveryTag, false, false));
      }
    }
  }

  /**
   * Routes a failed message to the retry queue (incrementing the retry counter), the dead-letter queue, or
   * rejects it outright. In every branch the original delivery is acknowledged exactly once, so the broker does
   * not keep an orphaned unacked message.
   */
  private Future<Void> routeAndSettle(long deliveryTag, RabbitMQMessage message, int retryCount) {
    final var headers = message.properties() == null ? null : message.properties().getHeaders();

    if (options.useRetryQueue && retryCount < options.maxRetries) {
      final Map<String, Object> newHeaders = new HashMap<>();
      if (headers != null) {
        newHeaders.putAll(headers);
      }
      newHeaders.put("x-retry-count", retryCount + 1);

      return queueManagement.publishToQueue(queueName + ".retry", message.body().toString(), buildRepublishProperties(message, newHeaders))
        .compose(v -> queueManagement.acknowledgeMessage(deliveryTag, false))
        .recover(err -> queueManagement.rejectMessage(deliveryTag, false, false));
    }

    if (options.useDeadLetterQueue) {
      final Map<String, Object> dlqHeaders = new HashMap<>();
      if (headers != null) {
        dlqHeaders.putAll(headers);
      }
      return queueManagement.publishToQueue(queueName + ".dlq", message.body().toString(), buildRepublishProperties(message, dlqHeaders))
        .compose(v -> queueManagement.acknowledgeMessage(deliveryTag, false))
        .recover(err -> queueManagement.rejectMessage(deliveryTag, false, false));
    }

    //? No retry/DLQ configured: reject without requeue so the message is dropped rather than poison the queue.
    return queueManagement.rejectMessage(deliveryTag, false, false);
  }

  private int retryCountOf(RabbitMQMessage message) {
    final var props = message.properties();
    if (props == null) {
      return 0;
    }
    final var headers = props.getHeaders();
    if (headers == null || !headers.containsKey("x-retry-count")) {
      return 0;
    }
    try {
      return Integer.parseInt(headers.get("x-retry-count").toString());
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  private AMQP.BasicProperties buildRepublishProperties(RabbitMQMessage message, Map<String, Object> headers) {
    final var builder = new AMQP.BasicProperties.Builder().headers(headers).deliveryMode(2);
    final var original = message.properties();
    if (original != null) {
      if (original.getContentType() != null) builder.contentType(original.getContentType());
      if (original.getContentEncoding() != null) builder.contentEncoding(original.getContentEncoding());
      if (original.getCorrelationId() != null) builder.correlationId(original.getCorrelationId());
      if (original.getReplyTo() != null) builder.replyTo(original.getReplyTo());
      if (original.getExpiration() != null) builder.expiration(original.getExpiration());
      if (original.getMessageId() != null) builder.messageId(original.getMessageId());
      if (original.getType() != null) builder.type(original.getType());
      if (original.getAppId() != null) builder.appId(original.getAppId());
      if (original.getPriority() != null) builder.priority(original.getPriority());
    }
    return builder.build();
  }

  public final Future<Void> stop() {
    return client.stop();
  }

  public final void clearResults() {
    successResults.clear();
    failErrors.clear();
  }

  public final int getSuccessCount() {
    return successResults.size();
  }

  public final int getFailCount() {
    return failErrors.size();
  }

  // ==================== Queue Management Methods ====================

  /**
   * Checks if the consumer is currently running.
   * @return true if consuming, false otherwise
   */
  public boolean isConsuming() {
    return consumer != null;
  }

  /**
   * Declares the queue for this consumer.
   * @return a Future that completes when the queue is declared
   */
  public Future<Void> declareQueue() {
    return queueManagement.declareQueue(queueName);
  }

  /**
   * Declares the queue with custom arguments.
   * @param arguments the queue arguments
   * @return a Future that completes when the queue is declared
   */
  public Future<Void> declareQueue(JsonObject arguments) {
    return queueManagement.declareQueue(queueName, true, false, false, arguments);
  }

  /**
   * Declares a queue with the message TTL.
   * @param ttlMs the TTL in milliseconds
   * @return a Future that completes when the queue is declared
   */
  public Future<Void> declareQueueWithTTL(long ttlMs) {
    final var args = new JsonObject().put("x-message-ttl", ttlMs);
    return queueManagement.declareQueue(queueName, true, false, false, args);
  }

  /**
   * Deletes the queue for this consumer.
   * @return a Future that completes when the queue is deleted
   */
  public Future<Void> deleteQueue() {
    return queueManagement.deleteQueue(queueName);
  }

  /**
   * Purges all messages from the queue for this consumer.
   * @return a Future that completes when the queue is purged
   */
  public Future<Void> purgeQueue() {
    return queueManagement.purgeQueue(queueName);
  }

  /**
   * Binds this queue to an exchange.
   * @param exchangeName the exchange name
   * @param routingKey the routing key
   * @return a Future that completes when the binding is created
   */
  public Future<Void> bindQueue(String exchangeName, String routingKey) {
    return queueManagement.bindQueue(queueName, exchangeName, routingKey);
  }

  /**
   * Unbinds this queue from an exchange.
   * @param exchangeName the exchange name
   * @param routingKey the routing key
   * @return a Future that completes when the binding is removed
   */
  public Future<Void> unbindQueue(String exchangeName, String routingKey) {
    return queueManagement.unbindQueue(queueName, exchangeName, routingKey);
  }

  /**
   * Declares a direct exchange.
   * @param exchangeName the exchange name
   * @return a Future that completes when the exchange is declared
   */
  public Future<Void> declareDirectExchange(String exchangeName) {
    return queueManagement.declareDirectExchange(exchangeName);
  }

  /**
   * Declares a fanout exchange.
   * @param exchangeName the exchange name
   * @return a Future that completes when the exchange is declared
   */
  public Future<Void> declareFanoutExchange(String exchangeName) {
    return queueManagement.declareFanoutExchange(exchangeName);
  }

  /**
   * Declares a topic exchange.
   * @param exchangeName the exchange name
   * @return a Future that completes when the exchange is declared
   */
  public Future<Void> declareTopicExchange(String exchangeName) {
    return queueManagement.declareTopicExchange(exchangeName);
  }

  /**
   * Deletes an exchange.
   * @param exchangeName the exchange name
   * @return a Future that completes when the exchange is deleted
   */
  public Future<Void> deleteExchange(String exchangeName) {
    return queueManagement.deleteExchange(exchangeName);
  }
}
