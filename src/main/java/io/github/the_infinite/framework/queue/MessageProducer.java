package io.github.the_infinite.framework.queue;

import com.rabbitmq.client.AMQP;

import java.util.Map;
import java.util.Objects;

import io.github.the_infinite.framework.data.DatabaseFactory;
import io.github.the_infinite.framework.logging.correlation.CorrelationContext;
import io.github.the_infinite.framework.logging.monitor.LogEvent;
import io.github.the_infinite.framework.logging.monitor.MonitorLogger;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.rabbitmq.RabbitMQClient;

@SuppressWarnings("unused")
public class MessageProducer {
  private final String queueName;
  private final CorrelationContext context;
  private final RabbitMQClient client;
  private final MonitorLogger logger;
  private final long timer;
  private final Vertx vertx;
  private final QueueManagement queueManagement;
  private AMQP.Queue.DeclareOk currentMetrics;

  public MessageProducer(Vertx vertx, String queueName, MonitorLogger logger) {
    this.vertx = vertx;
    this.logger = logger;
    this.queueName = queueName;
    this.client = DatabaseFactory.getQueueClient(vertx);
    this.queueManagement = new QueueManagement(this.client);
    this.context = CorrelationContext.from(vertx.getOrCreateContext());
    this.timer = vertx.setPeriodic(60_000, timerId ->
      queueManagement.getQueueInfo(queueName)
        .onSuccess(result -> {
          // Cast result to get metrics
          try {
            final var declareOk = (AMQP.Queue.DeclareOk) result;
            this.logger.info(context, LogEvent.create("Periodic message producer data", getClass().getName(), Map.of(
              "consumerCount", declareOk.getConsumerCount(),
              "messageCount", declareOk.getMessageCount(),
              "queueName", declareOk.getQueue()
            )));
            this.currentMetrics = declareOk;
          } catch (ClassCastException ignored) {
            // Handle if result format changes
          }
        }).onFailure(failure -> {
          final var usedMetrics = Objects.requireNonNullElse(currentMetrics, new AMQP.Queue.DeclareOk() {
            @Override
            public String getQueue() {
              return queueName;
            }

            @Override
            public int getMessageCount() {
              return -1;
            }

            @Override
            public int getConsumerCount() {
              return -1;
            }

            @Override
            public int protocolClassId() {
              return -1;
            }

            @Override
            public int protocolMethodId() {
              return -1;
            }

            @Override
            public String protocolMethodName() {
              return "N/A";
            }
          });
          this.logger.error(context, LogEvent.create(failure.getMessage(), getClass().getName(), Map.of(
            "consumerCount", usedMetrics.getConsumerCount(),
            "messageCount", usedMetrics.getMessageCount(),
            "queueName", queueName
          )));
        })
    );
  }

  private boolean notEquivalent(AMQP.Queue.DeclareOk metrics, AMQP.Queue.DeclareOk comparator) {
    return (metrics.getMessageCount() != comparator.getMessageCount()) || (metrics.getConsumerCount() != comparator.getConsumerCount());
  }

  public Future<Void> start() {
    if (client.isConnected()) {
      return Future.succeededFuture();
    }

    return client.start();
  }

  public Future<Void> stop() {
    if (vertx.cancelTimer(timer)) {
      this.logger.info(context, LogEvent.create("Producer has stopped", getClass().getName(), Map.of()));
    }
    return this.client.stop();
  }

  // ==================== Queue Management Methods ====================

  /**
   * Gets the QueueManagement utility for managing queues, exchanges, and bindings.
   * @return the QueueManagement instance
   */
  public QueueManagement getManager() {
    return queueManagement;
  }

  /**
   * Declares the default queue for this producer.
   * @return a Future that completes when the queue is declared
   */
  public Future<Void> declareQueue() {
    return queueManagement.declareQueue(queueName);
  }

  /**
   * Declares the default queue with custom arguments.
   * @param arguments the queue arguments (e.g., TTL, DLX)
   * @return a Future that completes when the queue is declared
   */
  public Future<Void> declareQueue(JsonObject arguments) {
    return queueManagement.declareQueue(queueName, true, false, false, arguments);
  }

  /**
   * Declares a queue with the message TTL (time to live).
   * @param queueName the queue name
   * @param ttlMs the TTL in milliseconds
   * @return a Future that completes when the queue is declared
   */
  public Future<Void> declareQueueWithTTL(String queueName, long ttlMs) {
    final var args = new JsonObject().put("x-message-ttl", ttlMs);
    return queueManagement.declareQueue(queueName, true, false, false, args);
  }

  /**
   * Declares a queue with a dead letter exchange.
   * @param queueName the queue name
   * @param dlxExchange the dead letter exchange name
   * @param dlxRoutingKey the dead letter routing key
   * @return a Future that completes when the queue is declared
   */
  public Future<Void> declareQueueWithDLX(String queueName, String dlxExchange, String dlxRoutingKey) {
    final var args = new JsonObject()
      .put("x-dead-letter-exchange", dlxExchange)
      .put("x-dead-letter-routing-key", dlxRoutingKey);
    return queueManagement.declareQueue(queueName, true, false, false, args);
  }

  /**
   * Declares a queue with both TTL and DLX.
   * @param queueName the queue name
   * @param ttlMs the TTL in milliseconds
   * @param dlxExchange the dead letter exchange name
   * @param dlxRoutingKey the dead letter routing key
   * @return a Future that completes when the queue is declared
   */
  public Future<Void> declareQueueWithTTLAndDLX(String queueName, long ttlMs, String dlxExchange, String dlxRoutingKey) {
    final var args = new JsonObject()
      .put("x-message-ttl", ttlMs)
      .put("x-dead-letter-exchange", dlxExchange)
      .put("x-dead-letter-routing-key", dlxRoutingKey);
    return queueManagement.declareQueue(queueName, true, false, false, args);
  }

  /**
   * Deletes the default queue.
   * @return a Future that completes when the queue is deleted
   */
  public Future<Void> deleteQueue() {
    return queueManagement.deleteQueue(queueName);
  }

  /**
   * Purges all messages from the default queue.
   * @return a Future that completes when the queue is purged
   */
  public Future<Void> purgeQueue() {
    return queueManagement.purgeQueue(queueName);
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

  /**
   * Binds a queue to an exchange.
   * @param queueName the queue name
   * @param exchangeName the exchange name
   * @param routingKey the routing key
   * @return a Future that completes when the binding is created
   */
  public Future<Void> bindQueue(String queueName, String exchangeName, String routingKey) {
    return queueManagement.bindQueue(queueName, exchangeName, routingKey);
  }

  /**
   * Binds the default queue to an exchange.
   * @param exchangeName the exchange name
   * @param routingKey the routing key
   * @return a Future that completes when the binding is created
   */
  public Future<Void> bindDefaultQueue(String exchangeName, String routingKey) {
    return bindQueue(this.queueName, exchangeName, routingKey);
  }

  /**
   * Unbinds a queue from an exchange.
   * @param queueName the queue name
   * @param exchangeName the exchange name
   * @param routingKey the routing key
   * @return a Future that completes when the binding is removed
   */
  public Future<Void> unbindQueue(String queueName, String exchangeName, String routingKey) {
    return queueManagement.unbindQueue(queueName, exchangeName, routingKey);
  }

  /**
   * Unbinds the default queue from an exchange.
   * @param exchangeName the exchange name
   * @param routingKey the routing key
   * @return a Future that completes when the binding is removed
   */
  public Future<Void> unbindDefaultQueue(String exchangeName, String routingKey) {
    return unbindQueue(this.queueName, exchangeName, routingKey);
  }

  /**
   * Publishes a message to the specified queue with a default priority of 1.
   * Ensures the necessary queue is declared before publishing if required.
   *
   * @param message the message to be published
   * @return a Future indicating the completion of the publish operation
   */
  public Future<Void> publish(String message) {
    return publish(message, new PublishOptions());
  }

  /**
   * Publishes a message to the specified queue with the given priority.
   * If the priority is less than 1, it defaults to 1. If the priority is greater than 9, it defaults to 9.
   * Ensures that the necessary queue is declared before publishing if required.
   *
   * @param message the message to be published
   * @param priority the priority of the message (range: 1 to 9)
   * @return a Future indicating the completion of the publish operation
   */
  public Future<Void> publish(String message, int priority) {
    final var options = new PublishOptions().setPriority(priority);
    return publish(message, options);
  }

  /**
   * Publishes a message with custom options.
   * Provides full control over message properties and publishing behavior.
   *
   * @param message the message to be published
   * @param options the publish options
   * @return a Future indicating the completion of the publish operation
   */
  public Future<Void> publish(String message, PublishOptions options) {
    if (currentMetrics == null) {
      return queueManagement.getQueueInfo(this.queueName)
        .compose(result -> {
          try {
            final var declareOk = (AMQP.Queue.DeclareOk) result;
            if (currentMetrics == null || notEquivalent(declareOk, currentMetrics)) {
              this.logger.info(context, LogEvent.create("The metrics for a message producer have changed", getClass().getName(), Map.of(
                "consumerCount", declareOk.getConsumerCount(),
                "messageCount", declareOk.getMessageCount(),
                "queueName", declareOk.getQueue()
              )));
            }
            this.currentMetrics = declareOk;
            return start().compose(v -> queueManagement.publishToQueue(this.queueName, message, options));
          } catch (ClassCastException e) {
            return Future.failedFuture(e);
          }
        });
    }

    return start().compose(t -> queueManagement.publishToQueue(this.queueName, message, options));
  }

  /**
   * Publishes a message to an exchange with a routing key.
   *
   * @param exchangeName the exchange name
   * @param routingKey the routing key
   * @param message the message to be published
   * @param options the publish options
   * @return a Future indicating the completion of the publish operation
   */
  public Future<Void> publishToExchange(String exchangeName, String routingKey, String message, PublishOptions options) {
    return start().compose(v -> queueManagement.publishToExchange(exchangeName, routingKey, message, options));
  }

  /**
   * Publishes a message to an exchange with a routing key using default options.
   *
   * @param exchangeName the exchange name
   * @param routingKey the routing key
   * @param message the message to be published
   * @return a Future indicating the completion of the publish operation
   */
  public Future<Void> publishToExchange(String exchangeName, String routingKey, String message) {
    return publishToExchange(exchangeName, routingKey, message, new PublishOptions());
  }
}
