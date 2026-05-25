package io.github.the_infinite.framework.controller;

import java.util.HashSet;
import java.util.Set;

import io.github.the_infinite.framework.response.ErrorResult;
import jakarta.validation.ConstraintViolation;

@SuppressWarnings("unused")
public final class ValidationError extends ErrorResult {
  private ValidationError(String message, Set<ConstraintViolation<?>> data) {
    super(message, data.stream().map(violation -> {
      final var path = violation.getPropertyPath().toString();
      return new ValidationErrorUnit(path, violation.getMessage());
    }).toArray(), 400);
  }

  public static <T> ValidationError of(String message, Set<ConstraintViolation<T>> set) {
    final var data = new HashSet<ConstraintViolation<?>>(set);
    return new ValidationError(message, data);
  }

  public static <T> ValidationError of(String message, ConstraintViolation<T>[] array) {
    return of(message, Set.of(array));
  }

  public static <T> ValidationError of(ConstraintViolation<T>[] array) {
    return of("Validation failed", array);
  }

  private record ValidationErrorUnit(String path, String message) {
  }
}
