package io.github.the_infinite.core.service;

@SuppressWarnings("unused")
public interface ServiceProvider {
  String getName();

  boolean isHealthy();
}
