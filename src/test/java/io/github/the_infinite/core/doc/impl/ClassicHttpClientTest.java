package io.github.the_infinite.core.doc.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.LinkedHashMap;
import java.util.Map;

import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

@ExtendWith(VertxExtension.class)
class ClassicHttpClientTest {
  @Test
  void execute_appendsQueryParameters(Vertx vertx, VertxTestContext testContext) {
    final var parameters = new LinkedHashMap<String, String>();
    parameters.put("alpha", "1");
    parameters.put("beta", "two words");

    vertx.createHttpServer()
      .requestHandler(request -> request.response().end(request.query()))
      .listen(0)
      .onComplete(testContext.succeeding(httpServer -> {
        final var client = new ClassicHttpClient(vertx);
        final var url = "http://localhost:%d/echo".formatted(httpServer.actualPort());

        client.execute("GET", url, Map.of(), null, parameters)
          .onComplete(testContext.succeeding(response -> httpServer.close().onComplete(closeResult -> {
            if (closeResult.failed()) {
              testContext.failNow(closeResult.cause());
              return;
            }

            testContext.verify(() -> {
              assertEquals(200, response.statusCode());
              assertEquals("alpha=1&beta=two+words", response.body());
            });
            testContext.completeNow();
          })));
      }));
  }
}
