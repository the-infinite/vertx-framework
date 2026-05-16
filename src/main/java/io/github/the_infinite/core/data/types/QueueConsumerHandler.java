package io.github.the_infinite.core.data.types;

import io.github.the_infinite.core.response.ServiceResult;

import java.util.function.Function;

import io.vertx.core.Future;

@SuppressWarnings("unused")
public interface QueueConsumerHandler<T, ResultType> {
  Future<ServiceResult<ResultType>> handle(T message, boolean redelivered, Function<Boolean, Future<Void>> ack, Function<Boolean, Future<Void>> nack);
}
