package io.github.the_infinite.framework.controller;

import io.github.the_infinite.framework.response.ErrorResult;

import java.util.HashSet;
import java.util.Set;

import jakarta.validation.ConstraintViolation;

@SuppressWarnings("unused")
public final class ValidationError extends ErrorResult {
  private ValidationError(String message, Set<ConstraintViolation<?>> data) {
    super(message, data.stream().map(violation -> {
      final var path = violation.getPropertyPath().toString();
      return new ValidationErrorUnit(path, violation.getMessage());
    }).toArray(), 400);
  }

  public static <T> ValidationError of(String message, ConstraintViolation<T>[] array) {
    final var data = new HashSet<ConstraintViolation<?>>(Set.of(array));
    return new ValidationError(message, data);
  }

  public static <T> ValidationError of(ConstraintViolation<T>[] array) {
    return of("Validation failed", array);
  }

  private record ValidationErrorUnit(String path, String message) {
  }
}
