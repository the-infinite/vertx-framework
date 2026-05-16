package io.github.the_infinite.framework.controller;

import io.github.the_infinite.framework.response.ErrorResult;

@SuppressWarnings("unused")
public class AuthorizationError extends ErrorResult {
  public AuthorizationError(String resource) {
    super("You have been denied access to the specified resource.", resource, 403);
  }
}
