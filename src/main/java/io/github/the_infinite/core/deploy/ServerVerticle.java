package io.github.the_infinite.core.deploy;

import io.github.the_infinite.core.ConfigurationRegistrant;

import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.core.http.HttpServer;

@SuppressWarnings("unused")
public final class ServerVerticle extends VerticleBase {
  private HttpServer server = null;

  @Override
  public Future<?> start() {
    return ConfigurationRegistrant.getInstance(vertx).serve().onSuccess(server -> this.server = server);
  }

  @Override
  public Future<?> stop() {
    if (this.server != null) {
      return this.server.close();
    }
    return Future.succeededFuture();
  }
}
