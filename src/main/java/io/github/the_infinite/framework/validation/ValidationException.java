package io.github.the_infinite.framework.validation;

import lombok.Getter;

@Getter
public class ValidationException extends RuntimeException {
  private final String field;

  public ValidationException(final String field, final String message) {
    super(message);
    this.field = field;
  }

  public ValidationException(final String field, final String message, final Throwable cause) {
    super(message, cause);
    this.field = field;
  }
}
