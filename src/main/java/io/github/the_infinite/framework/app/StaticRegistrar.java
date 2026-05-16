package io.github.the_infinite.framework.app;

import io.vertx.core.Vertx;

public interface StaticRegistrar {
  void registerStatic(Vertx vertx);
}
