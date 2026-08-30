package io.github.the_infinite.framework.retry;

import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;

/**
 * Production-grade retry engine built on top of Vert.x futures.
 * <p>
 * Every strategy exposed by this class returns a {@link Future} that is backed by a {@link Promise}. The
 * promise completes with the first successful result or fails once an optional attempt/timeout limit is
 * reached. Delays are scheduled through the Vert.x event loop (via {@link Vertx#setTimer}) so the calling
 * thread is never blocked by the backoff computation itself.
 * <p>
 * Supported backoff algorithms:
 * <ul>
 *   <li>{@link Backoff#EXPONENTIAL} - {@code base * 2^(attempt-1)} (with optional jitter).</li>
 *   <li>{@link Backoff#LINEAR} - {@code base * attempt} (with optional jitter).</li>
 *   <li>{@link Backoff#FIXED} - constant {@code base} delay between attempts.</li>
 *   <li>{@link Backoff#FIBONACCI} - Fibonacci growth ({@code base * fib(attempt)}).</li>
 *   <li>{@link Backoff#DECORRELATED_JITTER} - AWS-style decorrelated jitter (random in {@code [base, 3*previous]}).</li>
 *   <li>{@link Backoff#FULL_JITTER} - exponential ceiling with the actual delay randomized in {@code [0, ceiling]}.</li>
 * </ul>
 * <p>
 * Jitter (randomized spacing) is enabled by default and is what prevents a fleet of instances from
 * thundering the dependency in lockstep after an outage. Use {@link #noJitter()} or the {@code *NoJitter}
 * convenience methods when a deterministic schedule is required.
 */
@SuppressWarnings({"unused", "unchecked"})
public final class RetryStrategy {
  /**
   * Backoff algorithms supported by this strategy.
   */
  public enum Backoff {
    EXPONENTIAL,
    LINEAR,
    FIXED,
    FIBONACCI,
    DECORRELATED_JITTER,
    FULL_JITTER
  }

  private final Vertx vertx;
  private final Random random = new Random();

  private long baseDelayMs = 1_000L;
  private long maxDelayMs = 30_000L;
  private int maxAttempts = 10;
  private double jitterFactor = 0.5;
  private boolean jitter = true;
  private Backoff backoff = Backoff.EXPONENTIAL;
  private Predicate<Throwable> retryable = cause -> true;
  private Predicate<Object> successCondition = null;
  private long timeoutMs = 0L;
  private Handler<Throwable> onExhausted = null;

  private RetryStrategy(Vertx vertx) {
    this.vertx = vertx;
  }

  /**
   * Creates a retry strategy bound to the supplied Vert.x instance.
   */
  public static RetryStrategy create(Vertx vertx) {
    if (vertx == null) {
      throw new IllegalArgumentException("vertx cannot be null");
    }
    return new RetryStrategy(vertx);
  }

  private RetryStrategy(RetryStrategy other) {
    this.vertx = other.vertx;
    this.baseDelayMs = other.baseDelayMs;
    this.maxDelayMs = other.maxDelayMs;
    this.maxAttempts = other.maxAttempts;
    this.jitterFactor = other.jitterFactor;
    this.jitter = other.jitter;
    this.backoff = other.backoff;
    this.retryable = other.retryable;
    this.successCondition = other.successCondition;
    this.timeoutMs = other.timeoutMs;
    this.onExhausted = other.onExhausted;
  }

  // ==================== Configuration ====================

  public RetryStrategy withBaseDelay(long baseDelayMs) {
    if (baseDelayMs <= 0) {
      throw new IllegalArgumentException("baseDelayMs must be greater than 0");
    }
    this.baseDelayMs = baseDelayMs;
    return this;
  }

  public RetryStrategy withMaxDelay(long maxDelayMs) {
    if (maxDelayMs <= 0) {
      throw new IllegalArgumentException("maxDelayMs must be greater than 0");
    }
    this.maxDelayMs = maxDelayMs;
    return this;
  }

  public RetryStrategy withMaxAttempts(int maxAttempts) {
    if (maxAttempts < 1) {
      throw new IllegalArgumentException("maxAttempts must be greater than 0");
    }
    this.maxAttempts = maxAttempts;
    return this;
  }

  public RetryStrategy withJitter(boolean enabled) {
    this.jitter = enabled;
    return this;
  }

  public RetryStrategy withJitterFactor(double jitterFactor) {
    if (jitterFactor < 0 || jitterFactor > 1) {
      throw new IllegalArgumentException("jitterFactor must be between 0 and 1");
    }
    this.jitterFactor = jitterFactor;
    return this;
  }

  public RetryStrategy noJitter() {
    this.jitter = false;
    return this;
  }

  public RetryStrategy withBackoff(Backoff backoff) {
    this.backoff = backoff;
    return this;
  }

  /**
   * Restricts which failures are considered retryable. By default, every failure is retried.
   */
  public RetryStrategy retryOn(Predicate<Throwable> retryable) {
    this.retryable = retryable;
    return this;
  }

  /**
   * Marks an otherwise-successful result as a failure (and triggers a retry) unless it satisfies the
   * supplied predicate. Useful for "retry until condition holds" polling scenarios.
   */
  public <R> RetryStrategy until(Predicate<R> successCondition) {
    this.successCondition = successCondition == null ? null : (Predicate<Object>) successCondition;
    return this;
  }

  /**
   * Gives up once the supplied wall-clock deadline (in milliseconds from now) elapses, even if attempts to remain.
   */
  public RetryStrategy withTimeout(long timeoutMs) {
    if (timeoutMs < 0) {
      throw new IllegalArgumentException("timeoutMs cannot be negative");
    }
    this.timeoutMs = timeoutMs;
    return this;
  }

  /**
   * Invoked exactly once, in the event loop context, when every attempt has been exhausted. This is the
   * hook to use for terminal handling such as logging or shutting the process down ("just die").
   */
  public RetryStrategy onExhausted(Handler<Throwable> handler) {
    this.onExhausted = handler;
    return this;
  }

  // ==================== Core execution ====================

  /**
   * Runs the supplied action, retrying on failure according to the configured strategy. The returned future is
   * resolved either when the action succeeds (or satisfies {@link #until}) or when the attempt/timeout limit
   * is reached.
   */
  public <R> Future<R> run(Supplier<Future<R>> action) {
    return execute(ignored -> action.get());
  }

  /**
   * Variant that exposes the (1-based) attempt number to the action, which can be useful for logging or for
   * feeding the attempt count back into the operation being performed.
   */
  public <R> Future<R> run(Function<Integer, Future<R>> action) {
    return execute(action);
  }

  /**
   * Convenience wrapper for actions that do not naturally produce a future (e.g., a Runnable that may throw).
   * Retries whenever the runnable throws.
   */
  public Future<Void> run(Runnable action) {
    return execute(ignored -> {
      action.run();
      return Future.succeededFuture();
    });
  }

  // ==================== Named strategies ====================

  public <R> Future<R> withExponentialBackoff(Supplier<Future<R>> action) {
    return copy().withBackoff(Backoff.EXPONENTIAL).withJitter(true).execute(ignored -> action.get());
  }

  public <R> Future<R> withExponentialBackoffNoJitter(Supplier<Future<R>> action) {
    return copy().withBackoff(Backoff.EXPONENTIAL).noJitter().execute(ignored -> action.get());
  }

  public <R> Future<R> withLinearBackoff(Supplier<Future<R>> action) {
    return copy().withBackoff(Backoff.LINEAR).withJitter(true).execute(ignored -> action.get());
  }

  public <R> Future<R> withFibonacciBackoff(Supplier<Future<R>> action) {
    return copy().withBackoff(Backoff.FIBONACCI).withJitter(true).execute(ignored -> action.get());
  }

  public <R> Future<R> withFixedDelay(Supplier<Future<R>> action) {
    return copy().withBackoff(Backoff.FIXED).noJitter().execute(ignored -> action.get());
  }

  public <R> Future<R> withDecorrelatedJitter(Supplier<Future<R>> action) {
    return copy().withBackoff(Backoff.DECORRELATED_JITTER).noJitter().execute(ignored -> action.get());
  }

  public <R> Future<R> withFullJitter(Supplier<Future<R>> action) {
    return copy().withBackoff(Backoff.FULL_JITTER).noJitter().execute(ignored -> action.get());
  }

  /**
   * Retries until the action succeeds AND the result satisfies {@code successCondition}.
   */
  public <R> Future<R> until(Supplier<Future<R>> action, Predicate<R> successCondition) {
    return copy().until(successCondition).execute(ignored -> action.get());
  }

  /**
   * Polls the action until the result satisfies the predicate (alias of {@link #until}).
   */
  public <R> Future<R> pollUntil(Supplier<Future<R>> action, Predicate<R> successCondition) {
    return until(action, successCondition);
  }

  /**
   * Retries only for failures matching {@code retryable}; every other failure fails the future immediately.
   */
  public <R> Future<R> retryOn(Supplier<Future<R>> action, Predicate<Throwable> retryable) {
    return copy().retryOn(retryable).execute(ignored -> action.get());
  }

  /**
   * Retries with the supplied wall-clock timeout (in milliseconds) in addition to the attempt limit.
   */
  public <R> Future<R> withTimeout(long timeoutMs, Supplier<Future<R>> action) {
    return copy().withTimeout(timeoutMs).execute(ignored -> action.get());
  }

  // ==================== Engine ====================

  private RetryStrategy copy() {
    return new RetryStrategy(this);
  }

  private <R> Future<R> execute(Function<Integer, Future<R>> action) {
    final var promise = Promise.<R>promise();
    final var deadline = new AtomicLong(timeoutMs > 0 ? System.currentTimeMillis() + timeoutMs : 0L);
    final var previousDelay = new AtomicLong(baseDelayMs);
    attempt(action, 1, deadline, previousDelay, promise);
    return promise.future();
  }

  private <R> void attempt(
    Function<Integer, Future<R>> action,
    int attempt,
    AtomicLong deadline,
    AtomicLong previousDelay,
    Promise<R> promise
  ) {
    Future<R> result;
    try {
      result = action.apply(attempt);
    } catch (Throwable t) {
      result = Future.failedFuture(t);
    }

    if (result == null) {
      result = Future.failedFuture(new NullPointerException("Retry action returned a null Future"));
    }

    result.onComplete(ar -> {
      if (ar.succeeded()) {
        final R value = ar.result();
        if (successCondition != null && !successCondition.test(value)) {
          scheduleRetry(action, attempt, deadline, previousDelay, promise,
            new IllegalStateException("Action succeeded but the result did not satisfy the success condition"));
          return;
        }
        promise.complete(value);
        return;
      }

      final var cause = ar.cause();
      if (!retryable.test(cause)) {
        promise.fail(cause);
        return;
      }

      if (attempt >= maxAttempts) {
        failExhausted(promise, cause);
        return;
      }

      if (deadline.get() > 0 && System.currentTimeMillis() >= deadline.get()) {
        failExhausted(promise, new RetryExhaustedException(maxAttempts, cause, "retry deadline elapsed"));
        return;
      }

      final var delay = computeDelay(attempt, previousDelay);
      vertx.setTimer(delay, ignored -> attempt(action, attempt + 1, deadline, previousDelay, promise));
    });
  }

  private <R> void scheduleRetry(
    Function<Integer, Future<R>> action,
    int attempt,
    AtomicLong deadline,
    AtomicLong previousDelay,
    Promise<R> promise,
    Throwable cause
  ) {
    if (attempt >= maxAttempts) {
      failExhausted(promise, cause);
      return;
    }
    if (deadline.get() > 0 && System.currentTimeMillis() >= deadline.get()) {
      failExhausted(promise, new RetryExhaustedException(maxAttempts, cause, "retry deadline elapsed"));
      return;
    }
    final var delay = computeDelay(attempt, previousDelay);
    vertx.setTimer(delay, ignored -> attempt(action, attempt + 1, deadline, previousDelay, promise));
  }

  private <R> void failExhausted(Promise<R> promise, Throwable cause) {
    if (onExhausted != null) {
      try {
        onExhausted.handle(cause);
      } catch (Throwable ignored) {
        // terminal handler must never break the promise resolution
      }
    }
    promise.fail(new RetryExhaustedException(maxAttempts, cause));
  }

  private long computeDelay(int attempt, AtomicLong previousDelay) {
    final long raw = switch (backoff) {
      case FIXED -> baseDelayMs;
      case LINEAR -> baseDelayMs * attempt;
      case FIBONACCI -> baseDelayMs * fib(attempt);
      case DECORRELATED_JITTER -> {
        final long prev = previousDelay.get();
        final long lower = baseDelayMs;
        final long upper = Math.max(prev * 3L, baseDelayMs);
        final long next = (long) (lower + random.nextDouble() * (upper - lower));
        yield Math.min(next, maxDelayMs);
      }
      case FULL_JITTER, EXPONENTIAL -> exponentialDelay(attempt);
    };

    final long clamped = Math.min(raw, maxDelayMs);

    if (backoff == Backoff.FULL_JITTER) {
      // Actual sleep is randomized uniformly in [0, ceiling]; ceiling itself is the exponential value.
      return (long) (random.nextDouble() * clamped);
    }

    if (backoff == Backoff.DECORRELATED_JITTER) {
      previousDelay.set(clamped);
      return clamped;
    }

    if (jitter) {
      final double spread = clamped * jitterFactor;
      return (long) (clamped - spread + random.nextDouble() * (2.0 * spread));
    }

    return clamped;
  }

  private long exponentialDelay(int attempt) {
    // attempt is the 1-based count of attempts already performed; first retry waits `base`.
    final double power = Math.pow(2.0, Math.max(0, attempt - 1));
    return (long) (baseDelayMs * power);
  }

  private static long fib(int attempt) {
    if (attempt <= 1) {
      return 1L;
    }
    long a = 1L;
    long b = 1L;
    for (int i = 2; i < attempt; i++) {
      final long next = a + b;
      a = b;
      b = next;
    }
    return b;
  }
}
