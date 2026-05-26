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
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.rabbitmq.QueueOptions;
import io.vertx.rabbitmq.RabbitMQClient;
import io.vertx.rabbitmq.RabbitMQConsumer;

@SuppressWarnings({"unused", "CallToPrintStackTrace"})
public class QueueConsumer<T, ResultType> {
  private final Vertx vertx;
  private final RabbitMQClient client;
  private final String queueName;
  private final String consumerTag;
  private final QueueConsumerHandler<T, ResultType> handler;
  private final Function<String, T> deserializer;
  private final CorrelationContext context;
  private final MonitorLogger logger;
  private final MonitorLogger.Options options;
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
    client.queueDeclare(queueName, true, false, false)
      .onFailure(promise::fail).onSuccess(declareOk -> {
        logger.exec(context, LogEvent.create("Periodic queue statistics",
          getClass().getSimpleName(), Map.of(
            "queueName", queueName,
            "consumerCount", declareOk.getConsumerCount(),
            "messageCount", declareOk.getMessageCount(),
            "successCount", successResults.size(),
            "failCount", failErrors.size()
          )));
        promise.succeed();
      });
    return promise.future();
  }

  public final Future<Void> consume() {
    if (consumer != null) {
      return Future.succeededFuture();
    }

    final var promise = Promise.<Void>promise();
    final var queueOptions = new QueueOptions().setAutoAck(false);
    queueOptions.setConsumerTag(consumerTag);

    client.queueDeclare(queueName, true, false, false)
      .compose(declareOk -> {
        if (options.useRetryQueue) {
          return client.queueDeclare(queueName + ".retry", true, false, false, new JsonObject()
            .put("x-dead-letter-exchange", "")
            .put("x-dead-letter-routing-key", queueName)
            .put("x-message-ttl", options.retryInterval)).map(v -> declareOk);
        }
        return Future.succeededFuture(declareOk);
      })
      .compose(declareOk -> {
        if (options.useDeadLetterQueue) {
          return client.queueDeclare(queueName + ".dlq", true, false, false).map(v -> declareOk);
        }
        return Future.succeededFuture(declareOk);
      })
      .onFailure(promise::fail).onSuccess(declareOk -> {
        //? Begin consuming.
        client.basicConsumer(queueName, queueOptions)
          .onComplete(consumeResult -> {
            if (consumeResult.failed()) {
              promise.fail(consumeResult.cause());
              return;
            }

            consumer = consumeResult.result();

            //? Then log this one.
            this.logger.exec(context, LogEvent.create("A new consumer has been connected", getClass().getName(), Map.of(
              "queueName", queueName,
              "tag", consumerTag,
              "consumerCount", declareOk.getConsumerCount(),
              "messageCount", declareOk.getMessageCount()
            )));

            //? Add periodic tracking of progress here since we
            // need to periodically keep track of our queue statistics
            vertx.setPeriodic(600000, id -> getStatistics()
              .onFailure(cause -> logger.error(context, LogEvent.create("Failed to get queue statistics", getClass().getSimpleName(), Map.of(
                "queueName", queueName,
                "error", cause.getMessage(),
                "stackTrace", Arrays.stream(cause.getStackTrace()).map(StackTraceElement::toString)
              )))));

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

                //? This is okay.
                handler.handle(
                  messageData,
                  envelope.isRedeliver(),
                  multiple -> client.basicAck(envelope.getDeliveryTag(), multiple),
                  requeue -> client.basicNack(envelope.getDeliveryTag(), false, requeue)
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

                      client.basicPublish("", queueName + ".retry", props, Buffer.buffer(body))
                        .onSuccess(v -> client.basicAck(envelope.getDeliveryTag(), false))
                        .onFailure(v -> client.basicNack(envelope.getDeliveryTag(), false, true));
                    } else if (this.options.useDeadLetterQueue) {
                      client.basicPublish("", queueName + ".dlq", message.properties(), Buffer.buffer(body))
                        .onSuccess(v -> client.basicAck(envelope.getDeliveryTag(), false))
                        .onFailure(v -> client.basicNack(envelope.getDeliveryTag(), false, true));
                    } else {
                      client.basicNack(envelope.getDeliveryTag(), false, false);
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
                  client.basicPublish("", queueName + ".dlq", message.properties(), message.body())
                    .onSuccess(v -> client.basicAck(message.envelope().getDeliveryTag(), false))
                    .onFailure(v -> client.basicNack(message.envelope().getDeliveryTag(), false, true));
                } else {
                  client.basicNack(message.envelope().getDeliveryTag(), false, false);
                }
              }

              //? Return empty.
              return null;
            }));

            //? This is done.
            promise.succeed();
          });
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
}
