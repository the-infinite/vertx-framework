package io.github.the_infinite.core.service;

import java.util.Objects;
import java.util.Optional;

import io.vertx.circuitbreaker.CircuitBreaker;
import io.vertx.circuitbreaker.CircuitBreakerOptions;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.impl.future.PromiseImpl;

@SuppressWarnings({"OptionalUsedAsFieldOrParameterType", "unused"})
class ChainOfResponsibilityNodeImpl<T extends ServiceProvider> implements ChainOfResponsibilityNode<T> {
  final T provider;
  final CircuitBreaker breaker;
  Optional<ChainOfResponsibilityNodeImpl<T>> substitute;
  long successCount;
  long lastSucceededAt;
  long lastFailedAt;
  Throwable lastError;

  ChainOfResponsibilityNodeImpl(T provider, Vertx vertx, CircuitBreakerOptions options) {
    this.substitute = Optional.empty();
    this.provider = provider;
    this.breaker = CircuitBreaker.create(provider.getName(), vertx, options);
    this.successCount = 0;
    this.lastSucceededAt = 0;
    this.lastFailedAt = 0;
    this.lastError = null;

    //? Set the correct failure policy.
    breaker.failurePolicy(future -> {
      //? If this is not complete.
      if (!future.isComplete()) {
        return false;
      }

      //? Then we only return true
      return future.failed() && !provider.isHealthy();
    });
  }

  ChainOfResponsibilityNodeImpl(T provider, Vertx vertx) {
    this(provider, vertx, new CircuitBreakerOptions().setMaxFailures(5) // If you fail up to 5 times in a row, this is no longer useful.
      .setTimeout(10000) // If this does not work after 10 seconds, it has failed.
      .setFallbackOnFailure(false)  // Do not use fallback on failure.
      .setResetTimeout(30000) // Try again after 30 seconds.
    );
  }

  @Override
  public String getProviderName() {
    return this.provider.getName();
  }

  @Override
  public T getProvider() {
    return this.provider;
  }

  void reset() {
    this.breaker.reset();
    this.lastError = null;
    this.lastFailedAt = 0;
    this.successCount = 0;
    this.lastSucceededAt = 0;
  }

  /// Attempts to run the handler. It would fail immediately if this handler does not complete successfully.
  ///
  /// @param handler The handler function to attempt to run.
  @Override
  public <ReturnType> Future<ReturnType> run(CircuitBreakerHandler<ReturnType, T> handler) {
    final var returnPromise = new PromiseImpl<ReturnType>();
    breaker.executeAndReport(returnPromise, promise -> handler.run(provider).andThen(asyncResult -> {
      if (asyncResult.succeeded()) {
        successCount++;
        lastSucceededAt = System.currentTimeMillis();
        return;
      }

      lastFailedAt = System.currentTimeMillis();
      lastError = asyncResult.cause();
    }).onSuccess(promise::succeed).onFailure(promise::fail));
    return returnPromise.future();
  }

  ///  Attempts to run the handler with this handler function until one provider succeeds or no provider succeeds.
  ///
  /// @param handler The handler to run to completion.
  @Override
  public <ReturnType> Future<ReturnType> runTillEnd(CircuitBreakerHandler<ReturnType, T> handler) {
    final var returnPromise = new PromiseImpl<ReturnType>();
    final var coordinationPromise = new PromiseImpl<ReturnType>();
    breaker.executeAndReport(coordinationPromise, promise -> handler.run(provider).onSuccess(promise::succeed).onFailure(promise::fail));
    coordinationPromise.andThen(result -> {
      if (result.succeeded()) {
        this.successCount += 1;
        this.lastSucceededAt = System.currentTimeMillis();
        returnPromise.succeed(result.result());
        return;
      }

      //? If this has nothing next to handle it.
      if (substitute.isEmpty()) {
        this.lastFailedAt = System.currentTimeMillis();
        this.lastError = result.cause();
        returnPromise.fail(result.cause());
        return;
      }

      //? Then try again with the next.
      final var nextHandler = substitute.get();
      nextHandler.runTillEnd(handler).onSuccess(returnPromise::succeed).onFailure(returnPromise::fail);
    });

    //? Return the result promise.
    return returnPromise.future();
  }

  @SuppressWarnings("rawtypes")
  @Override
  public boolean equals(Object obj) {
    if (obj == null) {
      return false;
    }

    if (obj instanceof ChainOfResponsibilityNodeImpl chain) {
      return Objects.equals(chain.provider.getName(), this.provider.getName());
    }

    return false;
  }
}
