package io.github.the_infinite.framework.gateway;

import com.milestone.basilisk.vertx.BasiliskClient;

import java.util.List;

import io.github.the_infinite.framework.env.AppEnvironment;
import io.github.the_infinite.framework.retry.RetryStrategy;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import lombok.Getter;

public final class GatewayConnect {
  private static final int MAX_CONNECT_ATTEMPTS = 5;

  @Getter
  private static GatewayConnect instance;

  @Getter
  private final BasiliskClient client;

  private GatewayConnect(BasiliskClient client) {
    this.client = client;
  }

  public static Future<Void> setUp(
    Vertx vertx, List<String> mountPaths, String scheme,
    int weight, String authType
  ) {
    if (instance != null) {
      return Future.failedFuture(new IllegalStateException("Already initialized"));
    }
    if(mountPaths.isEmpty()) {
      return Future.succeededFuture();
    }

    final var env = AppEnvironment.getInstance();
    final var host = env.getRequired("BASILISK_HOST");
    final var port = env.getServerPort();
    final var busPort = Integer.parseInt(env.getRequired("BASILISK_BUS_PORT"));
    final var token = env.getRequired("BASILISK_TOKEN");
    final var baseUrl = env.getRequired("BASILISK_GATEWAY_URL");
    final var serviceId = env.getRequired("BASILISK_SERVICE_ID");
    final var fingerprint = env.getRequired("SERVICE_KEY");

    final var config = new BasiliskClient.BasiliskClientConfig(
      baseUrl, host, busPort, serviceId, fingerprint, mountPaths,
      scheme, host, port, weight, authType, token
    );

    return connectWithRetry(vertx, config).compose(client -> {
      instance = new GatewayConnect(client);
      return Future.succeededFuture();
    });
  }

  private static Future<BasiliskClient> connectWithRetry(
    Vertx vertx,
    BasiliskClient.BasiliskClientConfig config
  ) {
    return RetryStrategy.create(vertx)
      .withBaseDelay(1_000L)
      .withMaxDelay(30_000L)
      .withMaxAttempts(MAX_CONNECT_ATTEMPTS)
      .withExponentialBackoff(() -> BasiliskClient.connect(vertx, config));
  }
}
