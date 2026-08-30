package io.github.the_infinite.framework.retry;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.vertx.core.Future;
import io.vertx.core.Vertx;

class RetryStrategyTest {
  private Vertx vertx;

  @AfterEach
  void tearDown() throws Exception {
    if (vertx != null) {
      vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void succeedsAfterTransientFailuresWithBackoff() throws Exception {
    vertx = Vertx.vertx();
    final var attempts = new AtomicInteger(0);

    final var result = RetryStrategy.create(vertx)
      .withBaseDelay(20)
      .withMaxDelay(200)
      .withMaxAttempts(5)
      .withExponentialBackoff(() -> {
        final int n = attempts.incrementAndGet();
        if (n < 3) {
          return Future.failedFuture(new RuntimeException("boom-" + n));
        }
        return Future.succeededFuture("ok");
      })
      .toCompletionStage()
      .toCompletableFuture()
      .get(10, TimeUnit.SECONDS);

    assertEquals("ok", result);
    assertEquals(3, attempts.get());
  }

  @Test
  void exhaustsAndFailsWithRetryExhaustedException() {
    vertx = Vertx.vertx();
    final var attempts = new AtomicInteger(0);

    final var future = RetryStrategy.create(vertx)
      .withBaseDelay(10)
      .withMaxDelay(50)
      .withMaxAttempts(4)
      .withExponentialBackoff(() -> {
        attempts.incrementAndGet();
        return Future.failedFuture(new RuntimeException("always"));
      });

    final var ex = assertThrows(Exception.class, () ->
      future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS));
    final var cause = ex.getCause();
    assertInstanceOf(RetryExhaustedException.class, cause);
    assertEquals(4, attempts.get());
    assertEquals(4, ((RetryExhaustedException) cause).getAttempts());
  }

  @Test
  void doesNotRetryNonRetryableExceptions() {
    vertx = Vertx.vertx();
    final var attempts = new AtomicInteger(0);

    final var future = RetryStrategy.create(vertx)
      .withBaseDelay(10)
      .withMaxAttempts(5)
      .retryOn(() -> {
        attempts.incrementAndGet();
        return Future.failedFuture(new UnsupportedOperationException("fatal"));
      }, cause -> cause instanceof IllegalStateException);

    final var ex = assertThrows(Exception.class, () ->
      future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS));
    //? UnsupportedOperationException is not retryable, so the future fails on the very first attempt.
    assertInstanceOf(UnsupportedOperationException.class, ex.getCause());
    assertEquals(1, attempts.get());
  }

  @Test
  void retriesUntilPredicateIsSatisfied() throws Exception {
    vertx = Vertx.vertx();
    final var attempts = new AtomicInteger(0);

    final var result = RetryStrategy.create(vertx)
      .withBaseDelay(10)
      .withMaxAttempts(10)
      .until(() -> {
        final int n = attempts.incrementAndGet();
        return Future.succeededFuture(n);
      }, n -> n >= 4)
      .toCompletionStage()
      .toCompletableFuture()
      .get(10, TimeUnit.SECONDS);

    assertEquals(4, result);
    assertEquals(4, attempts.get());
  }

  @Test
  void retriesSynchronouslyThrowingAction() throws Exception {
    vertx = Vertx.vertx();
    final var attempts = new AtomicInteger(0);

    final var result = RetryStrategy.create(vertx)
      .withBaseDelay(10)
      .withMaxAttempts(5)
      .withExponentialBackoffNoJitter(() -> {
        final int n = attempts.incrementAndGet();
        if (n < 2) {
          throw new IllegalStateException("throw-" + n);
        }
        return Future.succeededFuture("recovered");
      })
      .toCompletionStage()
      .toCompletableFuture()
      .get(10, TimeUnit.SECONDS);

    assertEquals("recovered", result);
    assertEquals(2, attempts.get());
  }
}
