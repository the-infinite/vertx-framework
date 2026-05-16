package io.github.the_infinite.framework.controller;

import io.github.the_infinite.framework.RouteController;
import io.github.the_infinite.framework.doc.ExampleDTO;
import io.github.the_infinite.framework.doc.RouteDescription;

import io.vertx.core.Vertx;

public final class TestController extends RouteController {
  private final RouteController.RouteHandler<String> testController = (context, promise) -> promise.succeed(new Okay<>("test"));

  public TestController(Vertx vertx) {
    super(vertx, "/");
  }

  @Override
  public void registerRoutes() {
    RouteDescription description = RouteDescription.builder()
      .name("Greet")
      .description("Greets the user")
      .addPathParameter("name", "The name of the user to greet")
      .addQueryParameter("greeting", "The greeting message to use", "Hello")
      .addResponseDTO(200, ExampleDTO.class)
      .authenticationComment("Test endpoint")
      .build();
    mountGet("/greet/:name", description, testController);
  }
}
