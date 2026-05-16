package io.github.the_infinite.core.controller;

import io.github.the_infinite.core.response.ErrorResult;

@SuppressWarnings("unused")
public class BadConfiguration extends ErrorResult {
  public BadConfiguration(String message, String property) {
    super(message, property, 406);
  }

  public BadConfiguration(String name) {
    this("The associated configuration database is currently bad, inconsistent, or empty. Contact a system administrator for assistance.", name);
  }
}
