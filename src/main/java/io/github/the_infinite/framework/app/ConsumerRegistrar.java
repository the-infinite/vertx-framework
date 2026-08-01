package io.github.the_infinite.framework.app;

import java.util.List;

import io.github.the_infinite.framework.queue.QueueConsumer;
import io.vertx.core.Vertx;

public interface ConsumerRegistrar {
  List<QueueConsumer<?, ?>> consume(Vertx vertx);
}
