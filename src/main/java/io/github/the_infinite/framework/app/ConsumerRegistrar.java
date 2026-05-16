package io.github.the_infinite.framework.app;

import io.github.the_infinite.framework.queue.QueueConsumer;
import io.vertx.core.Vertx;

public interface ConsumerRegistrar {
  QueueConsumer<?, ?> consume(Vertx vertx);
}
