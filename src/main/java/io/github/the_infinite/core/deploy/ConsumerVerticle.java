package io.github.the_infinite.core.deploy;

import io.github.the_infinite.core.queue.QueueConsumer;

import io.vertx.core.Future;
import io.vertx.core.VerticleBase;

@SuppressWarnings("unused")
public final class ConsumerVerticle<T, TResult> extends VerticleBase {
  private final QueueConsumer<T, TResult> consumer;

  public ConsumerVerticle(QueueConsumer<T, TResult> handler) {
    super();
    this.consumer = handler;
  }

  @Override
  public Future<?> start() {
    return consumer.start().compose(result -> consumer.consume());
  }

  @Override
  public Future<?> stop() {
    if (consumer != null) {
      return consumer.stop();
    }
    return Future.succeededFuture();
  }
}
