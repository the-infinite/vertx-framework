package io.github.the_infinite.framework.retry;

import lombok.Getter;

/**
 * Thrown when a {@link RetryStrategy} exhausts every attempt (or its deadline elapses) without success.
 */
@Getter
public class RetryExhaustedException extends RuntimeException {
  /**
   * -- GETTER --
   *  The maximum number of attempts that were configured when this exception was produced.
   */
  private final int attempts;

  public RetryExhaustedException(int attempts, Throwable cause) {
    super("Retry attempts exhausted after %d attempt(s)".formatted(attempts), cause);
    this.attempts = attempts;
  }

  public RetryExhaustedException(int attempts, Throwable cause, String message) {
    super(message, cause);
    this.attempts = attempts;
  }
}
