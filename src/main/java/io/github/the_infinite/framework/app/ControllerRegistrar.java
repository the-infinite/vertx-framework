package io.github.the_infinite.framework.app;

import java.util.List;

import io.github.the_infinite.framework.RouteController;
import io.vertx.core.Vertx;

public interface ControllerRegistrar {
  List<? extends RouteController> mountAll(Vertx vertx);
}
