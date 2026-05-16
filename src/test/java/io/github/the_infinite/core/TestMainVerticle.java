package io.github.the_infinite.core;

import org.jetbrains.annotations.NotNull;

import io.github.the_infinite.core.controller.TestController;
import io.github.the_infinite.core.core.AppConfig;
import io.github.the_infinite.core.deploy.ServerVerticle;
import io.github.the_infinite.core.env.AppEnvironment;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.PoolOptions;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

@ExtendWith(VertxExtension.class)
@SuppressWarnings("unused")
public class TestMainVerticle {
  static String deploymentId;

  @BeforeAll
  static void deploy_verticle(Vertx vertx, VertxTestContext testContext) {
    //? Set up the configuration registrant.
    ConfigurationRegistrant.setUp(vertx, null);
    final var config = ConfigurationRegistrant.getInstance(vertx);

    //? Mount the controllers.
    config.mountController(new TestController(vertx));

    //? Do what is necessary.
    vertx.deployVerticle(ServerVerticle::new, new DeploymentOptions().setInstances(AppEnvironment.getInstance().getServerCount())).onComplete(testContext.succeeding(id -> {
      deploymentId = id;
      testContext.completeNow();
    }));
  }

  @Test
  void verticle_deployed(Vertx vertx, VertxTestContext testContext) {
    assert vertx.deploymentIDs().contains(deploymentId);
    testContext.completeNow();
  }

  @Test
  void verticle_reachable(Vertx vertx, @NotNull VertxTestContext testContext) {
    final var env = AppEnvironment.getInstance();
    final var client = vertx.createHttpClient(new PoolOptions());

    //? Send the request then.
    client.request(HttpMethod.GET, env.getServerPort(), "localhost", "/greet/tobi")
      .compose(req -> req.send().compose(HttpClientResponse::body))
      .onComplete(testContext
        .succeeding(body -> testContext.verify(() -> {
            assert body.toString().contains("test");
            testContext.completeNow();
          })
        )
      );
  }

  @Test
  void documentation_reachable(Vertx vertx, @NotNull VertxTestContext testContext) {
    final var env = AppEnvironment.getInstance();
    final var client = vertx.createHttpClient(new PoolOptions());

    //? Send the request then.
    client.request(HttpMethod.GET, env.getServerPort(), "localhost", "/")
      .compose(req -> req.send().onComplete(ar -> {
        if (ar.succeeded()) {
            var response = ar.result();
            assert response.headers().get("Content-Type").contains("text/html");
        }
      }).compose(HttpClientResponse::body))
      .onComplete(testContext
        .succeeding(body -> testContext.verify(() -> {
            assert body.toString().contains("<!DOCTYPE html>");
            assert body.toString().contains("API Documentation");
            testContext.completeNow();
          })
        )
      );
  }

  @Test
  void database_reachable(Vertx vertx, VertxTestContext testContext) {
    final var env = AppEnvironment.getInstance();
    final var config = new AppConfig(vertx);
    final var db = config.createAppDatabase();

    db.andThen(createResult -> {
      if (createResult.failed()) {
        testContext.failNow(createResult.cause());
      } else {
        testContext.completeNow();
      }
    });
  }
}
