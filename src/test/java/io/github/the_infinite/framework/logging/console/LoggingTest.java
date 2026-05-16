package io.github.the_infinite.framework.logging.console;

import io.github.the_infinite.framework.ConfigurationRegistrant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

@ExtendWith(VertxExtension.class)
public class LoggingTest {

  @Test
  void testLogging(Vertx vertx, VertxTestContext testContext) {
    ConfigurationRegistrant.setUp(vertx);
    ConsoleLogger logger = ConsoleLogger.getInstance(vertx);

    logger.info("This is an info message from SLF4J")
      .onComplete(testContext.succeeding(v -> logger.error("This is an error message from SLF4J")
        .onComplete(testContext.succeeding(v2 -> testContext.completeNow()))));
  }
}
