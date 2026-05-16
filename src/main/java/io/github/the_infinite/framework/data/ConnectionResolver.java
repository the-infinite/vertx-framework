package io.github.the_infinite.framework.data;

import io.github.the_infinite.framework.env.AppEnvironment;

import org.hibernate.reactive.pool.impl.DefaultSqlClientPoolConfiguration;

import java.net.URI;
import java.util.Objects;

import io.vertx.core.net.ClientSSLOptions;
import io.vertx.pgclient.PgConnectOptions;

public final class ConnectionResolver extends DefaultSqlClientPoolConfiguration {
  @Override
  public PgConnectOptions connectOptions(URI uri) {
    final var env = AppEnvironment.getInstance();
    final var options = PgConnectOptions.fromUri(env.getPgUrl());
    options.setSslOptions(Objects.requireNonNullElse(options.getSslOptions(), new ClientSSLOptions()));
    return options;
  }
}
