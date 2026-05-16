package io.github.the_infinite.framework.controller;

import io.github.the_infinite.framework.response.ErrorResult;

@SuppressWarnings("unused")
public class GenericError extends ErrorResult {
  public GenericError(String message, Object data, int code) {
    super(message, data, code);
  }

  public GenericError(String message, Object data) {
    this(message, data, 500);
  }

  public GenericError(String message) {
    this(message, null, 500);
  }
}
