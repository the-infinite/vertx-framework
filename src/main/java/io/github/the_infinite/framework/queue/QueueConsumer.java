package io.github.the_infinite.framework.queue;

import com.rabbitmq.client.AMQP;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
      .compose(v -> queueManagement.createConsumer(queueName, consumerTag))
      .onFailure(promise::fail)
      .onSuccess(rabbitConsumer -> {
        consumer = rabbitConsumer;

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
        consumer.handler(message -> vertx.executeBlocking(() -> {
          try {
            final var envelope = message.envelope();
            final var jsonObject = message.body().toJsonObject();
            final var body = jsonObject.encode();
            final var messageData = deserializer.apply(body);
            final var messageType = messageData.getClass();
            final var timeStart = logger.time(context, LogEvent.create("Message handled by consumer", getClass().getName(), Map.of(
              "queue", queueName,
              "consumerTag", consumerTag,
              "messageType", messageType.getName(),
              "startedAt", System.currentTimeMillis(),
              "messageId", envelope.getDeliveryTag()
            )));

            //? Handle the message
            handler.handle(
              messageData,
              envelope.isRedeliver(),
              multiple -> queueManagement.acknowledgeMessage(envelope.getDeliveryTag(), multiple),
              requeue -> queueManagement.rejectMessage(envelope.getDeliveryTag(), false, requeue)
            ).onComplete(handleResult -> {
              //? Add this to our list of good results...
              if (handleResult.succeeded()) {
                successResults.put(envelope.getDeliveryTag(), handleResult.result().getData());
                logger.exec(context, LogEvent.create("Message processed successfully", getClass().getSimpleName(), Map.of(
                  "queue", queueName,
                  "consumerTag", consumerTag,
                  "messageType", messageType.getName(),
                  "messageId", envelope.getDeliveryTag(),
                  "timestamp", System.currentTimeMillis(),
                  "input", jsonObject.getMap(),
                  "result", handleResult.result().serialize()
                )));
              }
              //? This is okay then.
              else {
                if (AppEnvironment.getInstance().getKind() != AppEnvironment.EnvironmentKind.PRODUCTION) {
                  handleResult.cause().printStackTrace();
                }
                failErrors.put(envelope.getDeliveryTag(), handleResult.cause());
                logger.error(context, LogEvent.create("Failed to process message", getClass().getSimpleName(), Map.of(
                  "queue", queueName,
                  "input", jsonObject.getMap(),
                  "consumerTag", consumerTag,
                  "messageType", messageType.getName(),
                  "messageId", envelope.getDeliveryTag(),
                  "timestamp", System.currentTimeMillis(),
                  "error", handleResult.cause().getMessage(),
                  "stackTrace", Arrays.stream(handleResult.cause().getStackTrace()).map(StackTraceElement::toString)
                )));

                //? Handle retries and DLQ.
                final var headers = message.properties().getHeaders();
                int retryCount = 0;
                if (headers != null && headers.containsKey("x-retry-count")) {
                  retryCount = Integer.parseInt(headers.get("x-retry-count").toString());
                }

                if (this.options.useRetryQueue && retryCount < this.options.maxRetries) {
                  final Map<String, Object> newHeaders = (headers == null) ? new HashMap<>() : new HashMap<>(headers);
                  newHeaders.put("x-retry-count", retryCount + 1);

                  final var originalProps = message.properties();
                  final var props = new AMQP.BasicProperties(
                    originalProps.getContentType(),
                    originalProps.getContentEncoding(),
                    newHeaders,
                    originalProps.getDeliveryMode(),
                    originalProps.getPriority(),
                    originalProps.getCorrelationId(),
                    originalProps.getReplyTo(),
                    originalProps.getExpiration(),
                    originalProps.getMessageId(),
                    originalProps.getTimestamp(),
                    originalProps.getType(),
                    originalProps.getUserId(),
                    originalProps.getAppId(),
                    null
                  );

                  queueManagement.publishToQueue(queueName + ".retry", body, props)
                    .onSuccess(v -> queueManagement.acknowledgeMessage(envelope.getDeliveryTag(), false))
                    .onFailure(v -> queueManagement.rejectMessage(envelope.getDeliveryTag(), false, true));
                } else if (this.options.useDeadLetterQueue) {
                  queueManagement.publishToQueue(queueName + ".dlq", body, (AMQP.BasicProperties) message.properties())
                    .onSuccess(v -> queueManagement.acknowledgeMessage(envelope.getDeliveryTag(), false))
                    .onFailure(v -> queueManagement.rejectMessage(envelope.getDeliveryTag(), false, true));
                } else {
                  queueManagement.rejectMessage(envelope.getDeliveryTag(), false, false);
                }
              }

              //? End this one.
              timeStart.end();
            });
          } catch (Exception ex) {
            if (AppEnvironment.getInstance().getKind() != AppEnvironment.EnvironmentKind.PRODUCTION) {
              ex.printStackTrace();
            }
            logger.error(context, LogEvent.create("Failed to handle message", getClass().getSimpleName(), Map.of(
              "queue", queueName,
              "consumerTag", consumerTag,
              "error", ex.getMessage(),
              "stackTrace", Arrays.stream(ex.getStackTrace()).map(StackTraceElement::toString)
            )));

            if (this.options.useDeadLetterQueue) {
              queueManagement.publishToQueue(queueName + ".dlq", message.body().toString(), (AMQP.BasicProperties) message.properties())
                .onSuccess(v -> queueManagement.acknowledgeMessage(message.envelope().getDeliveryTag(), false))
                .onFailure(v -> queueManagement.rejectMessage(message.envelope().getDeliveryTag(), false, true));
            } else {
              queueManagement.rejectMessage(message.envelope().getDeliveryTag(), false, false).onComplete(ar -> {
              });
            }
          }

          //? Return empty.
          return null;
        }));

        //? This is done.
        promise.succeed();
      });

    return promise.future();
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
