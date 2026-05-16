package io.github.the_infinite.framework.service;

import io.vertx.core.Future;

@SuppressWarnings("unused")
public interface ChainOfResponsibilityNode<T extends ServiceProvider> {
  String getProviderName();

  T getProvider();

  <ReturnType> Future<ReturnType> run(CircuitBreakerHandler<ReturnType, T> handler);

  <ReturnType> Future<ReturnType> runTillEnd(CircuitBreakerHandler<ReturnType, T> handler);
}
