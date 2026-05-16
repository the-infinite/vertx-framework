package io.github.the_infinite.core.service;

import java.util.function.Predicate;

import io.vertx.core.Future;

public interface CircuitBreakerHandler<ReturnType, T extends ServiceProvider> extends Predicate<Future<ReturnType>> {
  Future<ReturnType> run(T provider);
}
