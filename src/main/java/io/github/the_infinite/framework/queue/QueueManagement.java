package io.github.the_infinite.framework.queue;

import com.rabbitmq.client.AMQP;

import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.rabbitmq.QueueOptions;
import io.vertx.rabbitmq.RabbitMQClient;
import io.vertx.rabbitmq.RabbitMQConsumer;

/**
 * Utility class for managing RabbitMQ queues, exchanges, bindings, and message operations.
 * Provides convenience methods without exposing the underlying RabbitMQClient directly.
 */
public class QueueManagement {
  private final RabbitMQClient client;

  public QueueManagement(RabbitMQClient client) {
    this.client = client;
  }


  /**
   * Declares a durable queue.
   * @param queueName the queue name
   * @return a Future that completes when the queue is declared
   */
  public Future<Void> declareQueue(String queueName) {
    return declareQueue(queueName, true, false, false);
  }

  /**
   * Declares a queue with specified properties.
   * @param queueName the queue name
   * @param durable whether the queue should survive broker restarts
   * @param exclusive whether the queue should be exclusive to this connection
   * @param autoDelete whether the queue should be auto-deleted when no longer in use
   * @return a Future that completes when the queue is declared
   */
  public Future<Void> declareQueue(String queueName, boolean durable, boolean exclusive, boolean autoDelete) {
    return client.queueDeclare(queueName, durable, exclusive, autoDelete).mapEmpty();
  }

  /**
   * Declares a queue with additional arguments (e.g., TTL, DLX).
   * @param queueName the queue name
   * @param durable whether the queue should survive broker restarts
   * @param exclusive whether the queue should be exclusive to this connection
   * @param autoDelete whether the queue should be auto-deleted when no longer in use
   * @param arguments additional queue arguments
   * @return a Future that completes when the queue is declared
   */
  public Future<Void> declareQueue(String queueName, boolean durable, boolean exclusive, boolean autoDelete, JsonObject arguments) {
    return client.queueDeclare(queueName, durable, exclusive, autoDelete, arguments).mapEmpty();
  }

  /**
   * Deletes a queue.
   * @param queueName the queue name
   * @return a Future that completes when the queue is deleted
   */
  public Future<Void> deleteQueue(String queueName) {
    return client.queueDelete(queueName).mapEmpty();
  }

  /**
   * Purges all messages from a queue (only if connected).
   * Note: This is a manual operation - messages can be purged manually via RabbitMQ management.
   * @param queueName the queue name
   * @return a Future that completes when the purge is initiated
   */
  public Future<Void> purgeQueue(String queueName) {
    // RabbitMQ client doesn't expose purge, but we can use queueDelete + redeclare
    // This is a workaround - in production use RabbitMQ management API
    return deleteQueue(queueName)
      .compose(_ -> declareQueue(queueName, true, false, false));
  }

  /**
   * Declares a direct exchange.
   * @param exchangeName the exchange name
   * @return a Future that completes when the exchange is declared
   */
  public Future<Void> declareDirectExchange(String exchangeName) {
    return declareExchange(exchangeName, "direct", true, false);
  }

  /**
   * Declares a fanout exchange.
   * @param exchangeName the exchange name
   * @return a Future that completes when the exchange is declared
   */
  public Future<Void> declareFanoutExchange(String exchangeName) {
    return declareExchange(exchangeName, "fanout", true, false);
  }

  /**
   * Declares a topic exchange.
   * @param exchangeName the exchange name
   * @return a Future that completes when the exchange is declared
   */
  public Future<Void> declareTopicExchange(String exchangeName) {
    return declareExchange(exchangeName, "topic", true, false);
  }

  /**
   * Declares an exchange with specified properties.
   * @param exchangeName the exchange name
   * @param type the exchange type (direct, fanout, topic, headers)
   * @param durable whether the exchange should survive broker restarts
   * @param autoDelete whether the exchange should be auto-deleted when no longer in use
   * @return a Future that completes when the exchange is declared
   */
  public Future<Void> declareExchange(String exchangeName, String type, boolean durable, boolean autoDelete) {
    return client.exchangeDeclare(exchangeName, type, durable, autoDelete).mapEmpty();
  }

  /**
   * Declares an exchange with additional arguments.
   * @param exchangeName the exchange name
   * @param type the exchange type
   * @param durable whether the exchange should survive broker restarts
   * @param autoDelete whether the exchange should be auto-deleted when no longer in use
   * @param arguments additional exchange arguments
   * @return a Future that completes when the exchange is declared
   */
  public Future<Void> declareExchange(String exchangeName, String type, boolean durable, boolean autoDelete, JsonObject arguments) {
    return client.exchangeDeclare(exchangeName, type, durable, autoDelete, arguments).mapEmpty();
  }

  /**
   * Deletes an exchange.
   * @param exchangeName the exchange name
   * @return a Future that completes when the exchange is deleted
   */
  public Future<Void> deleteExchange(String exchangeName) {
    return client.exchangeDelete(exchangeName).mapEmpty();
  }

  /**
   * Binds a queue to an exchange.
   * @param queueName the queue name
   * @param exchangeName the exchange name
   * @param routingKey the routing key
   * @return a Future that completes when the binding is created
   */
  public Future<Void> bindQueue(String queueName, String exchangeName, String routingKey) {
    return client.queueBind(queueName, exchangeName, routingKey).mapEmpty();
  }

  /**
   * Unbinds a queue from an exchange.
   * @param queueName the queue name
   * @param exchangeName the exchange name
   * @param routingKey the routing key
   * @return a Future that completes when the binding is removed
   */
  public Future<Void> unbindQueue(String queueName, String exchangeName, String routingKey) {
    return client.queueUnbind(queueName, exchangeName, routingKey).mapEmpty();
  }

  // ==================== Message Publishing Methods ====================

  /**
   * Publishes a message to a queue with default properties.
   * @param queueName the queue name
   * @param message the message body
   * @return a Future that completes when the message is published
   */
  public Future<Void> publishToQueue(String queueName, String message) {
    return publishToExchange("", queueName, null, message);
  }

  /**
   * Publishes a message to a queue with specified properties.
   * @param queueName the queue name
   * @param message the message body
   * @param properties the message properties
   * @return a Future that completes when the message is published
   */
  public Future<Void> publishToQueue(String queueName, String message, AMQP.BasicProperties properties) {
    return publishToExchange("", queueName, properties, message);
  }

  /**
   * Publishes a message to an exchange with a routing key.
   * @param exchangeName the exchange name
   * @param routingKey the routing key
   * @param message the message body
   * @return a Future that completes when the message is published
   */
  public Future<Void> publishToExchange(String exchangeName, String routingKey, String message) {
    return publishToExchange(exchangeName, routingKey, null, message);
  }

  /**
   * Publishes a message to an exchange with specified properties and routing key.
   * @param exchangeName the exchange name
   * @param routingKey the routing key
   * @param properties the message properties
   * @param message the message body
   * @return a Future that completes when the message is published
   */
  public Future<Void> publishToExchange(String exchangeName, String routingKey, AMQP.BasicProperties properties, String message) {
    return client.basicPublish(exchangeName, routingKey, properties, Buffer.buffer(message)).mapEmpty();
  }

  /**
   * Publishes a message to an exchange with a routing key using PublishOptions.
   * @param exchangeName the exchange name
   * @param routingKey the routing key
   * @param message the message body
   * @param options the publish options
   * @return a Future that completes when the message is published
   */
  public Future<Void> publishToExchange(String exchangeName, String routingKey, String message, PublishOptions options) {
    final var props = buildBasicProperties(options);
    return publishToExchange(exchangeName, routingKey, props, message);
  }

  /**
   * Publishes a message to a queue using PublishOptions.
   * @param queueName the queue name
   * @param message the message body
   * @param options the publish options
   * @return a Future that completes when the message is published
   */
  public Future<Void> publishToQueue(String queueName, String message, PublishOptions options) {
    return publishToExchange("", queueName, message, options);
  }

  // ==================== Message Consuming Methods ====================

  /**
   * Creates a basic consumer for a queue with auto-ack disabled.
   * @param queueName the queue name
   * @param consumerTag optional consumer tag for identification
   * @return a Future that completes with the RabbitMQConsumer
   */
  public Future<RabbitMQConsumer> createConsumer(String queueName, String consumerTag) {
    final var options = new QueueOptions().setAutoAck(false);
    if (consumerTag != null && !consumerTag.isEmpty()) {
      options.setConsumerTag(consumerTag);
    }
    return client.basicConsumer(queueName, options);
  }

  /**
   * Creates a basic consumer for a queue with manual ack handling.
   * @param queueName the queue name
   * @return a Future that completes with the RabbitMQConsumer
   */
  public Future<RabbitMQConsumer> createConsumer(String queueName) {
    return createConsumer(queueName, null);
  }

  /**
   * Acknowledges a message delivery.
   * @param deliveryTag the delivery tag of the message
   * @param multiple whether to acknowledge multiple messages
   * @return a Future that completes when the ack is sent
   */
  public Future<Void> acknowledgeMessage(long deliveryTag, boolean multiple) {
    return client.basicAck(deliveryTag, multiple).mapEmpty();
  }

  /**
   * Rejects a message delivery.
   * @param deliveryTag the delivery tag of the message
   * @param multiple whether to reject multiple messages
   * @param requeue whether to requeue the message
   * @return a Future that completes when the nack is sent
   */
  public Future<Void> rejectMessage(long deliveryTag, boolean multiple, boolean requeue) {
    return client.basicNack(deliveryTag, multiple, requeue).mapEmpty();
  }

  /**
   * Gets queue declaration info.
   * @param queueName the queue name
   * @return a Future that completes with the queue declaration result
   */
  public Future<?> getQueueInfo(String queueName) {
    return client.queueDeclare(queueName, true, false, false);
  }

  // ==================== Helper Methods ====================

  /**
   * Builds AMQP.BasicProperties from PublishOptions.
   * @param options the publish options
   * @return the AMQP.BasicProperties
   */
  private AMQP.BasicProperties buildBasicProperties(PublishOptions options) {
    return new AMQP.BasicProperties(
      options.getContentType(),
      options.getContentEncoding(),
      options.getHeaders().isEmpty() ? null : options.getHeaders(),
      options.getDeliveryMode(),
      options.getPriority(),
      options.getCorrelationId(),
      options.getReplyTo(),
      options.getExpiration() != null ? Long.toString(options.getExpiration()) : null,
      options.getMessageId(),
      null, // timestamp - will be set by broker
      options.getType(),
      options.getUserId(),
      options.getAppId(),
      null
    );
  }
}
