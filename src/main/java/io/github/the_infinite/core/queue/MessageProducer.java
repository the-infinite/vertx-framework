package io.github.the_infinite.core.queue;

import com.rabbitmq.client.AMQP;

import io.github.the_infinite.core.data.DatabaseFactory;
import io.github.the_infinite.core.logging.correlation.CorrelationContext;
import io.github.the_infinite.core.logging.monitor.LogEvent;
import io.github.the_infinite.core.logging.monitor.MonitorLogger;

import java.util.Map;
import java.util.Objects;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.rabbitmq.RabbitMQClient;

@SuppressWarnings("unused")
public class MessageProducer {
  private final String queueName;
  private final CorrelationContext context;
  private final RabbitMQClient client;
  private final MonitorLogger logger;
  private final long timer;
  private final Vertx vertx;
  private AMQP.Queue.DeclareOk currentMetrics;

  public MessageProducer(Vertx vertx, String queueName, MonitorLogger logger) {
    this.vertx = vertx;
    this.logger = logger;
    this.queueName = queueName;
    this.client = DatabaseFactory.getQueueClient(vertx);
    this.context = CorrelationContext.from(vertx.getOrCreateContext());
    this.timer = vertx.setPeriodic(60_000, timerId ->
      client.queueDeclare(queueName, true, false, false)
        .onSuccess(declareOk -> {
          this.logger.info(context, LogEvent.create("Periodic message producer data", getClass().getName(), Map.of(
            "consumerCount", declareOk.getConsumerCount(),
            "messageCount", declareOk.getMessageCount(),
            "queueName", declareOk.getQueue()
          )));
          this.currentMetrics = declareOk;
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

  /**
   * Publishes a message to the specified queue with a default priority of 1.
   * Ensures the necessary queue is declared before publishing if required.
   *
   * @param message the message to be published
   * @return a Future indicating the completion of the publish operation
   */
  Future<Void> publish(String message) {
    return publish(message, 1);
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
  Future<Void> publish(String message, int priority) {
    if (priority < 1) {
      priority = 1;
    } else if (priority > 9) {
      priority = 9;
    }

    final var props = new AMQP.BasicProperties(
      null,
      null,
      null,
      null,
      priority,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null
    );

    if (currentMetrics == null) {
      return client.queueDeclare(this.queueName, true, false, false)
        .compose(declareOk -> {
          if (currentMetrics == null || notEquivalent(declareOk, currentMetrics)) {
            this.logger.info(context, LogEvent.create("The metrics for a message producer have changed", getClass().getName(), Map.of(
              "consumerCount", declareOk.getConsumerCount(),
              "messageCount", declareOk.getMessageCount(),
              "queueName", declareOk.getQueue()
            )));
          }
          this.currentMetrics = declareOk;
          return Future.succeededFuture(declareOk);
        }).compose(declareOk -> start().compose(v -> client.basicPublish("", this.queueName, props, Buffer.buffer(message))));
    }

    //? Just send this as is.
    return start().compose(t -> client.basicPublish("", this.queueName, props, Buffer.buffer(message)));
  }
}
