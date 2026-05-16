package io.github.the_infinite.core.controller;

import io.github.the_infinite.core.response.ErrorResult;

@SuppressWarnings("unused")
public class AuthenticationError extends ErrorResult {
  public AuthenticationError(String message, Object data) {
    super(message, data, 401);
  }

  public AuthenticationError(Object data) {
    this("Your session is expired or invalid.", data);
  }
}
