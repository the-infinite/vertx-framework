package io.github.the_infinite.framework.service;

@SuppressWarnings("unused")
public interface ServiceProvider {
  String getName();

  boolean isHealthy();
}
