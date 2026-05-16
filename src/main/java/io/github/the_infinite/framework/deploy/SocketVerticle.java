package io.github.the_infinite.framework.deploy;

import io.github.the_infinite.framework.ConfigurationRegistrant;

import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.core.net.NetServer;

@SuppressWarnings("unused")
public final class SocketVerticle extends VerticleBase {
  private NetServer server = null;

  @Override
  public Future<?> start() {
    return ConfigurationRegistrant.getInstance(vertx).socket().onSuccess(rawSocket -> this.server = rawSocket);
  }

  @Override
  public Future<?> stop() {
    if (this.server != null) {
      return this.server.close();
    }
    return Future.succeededFuture();
  }
}
