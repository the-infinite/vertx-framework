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
      final String overrideUrl = System.getProperty("pg.url.override");
      final String urlToUse = overrideUrl != null ? overrideUrl : uri.toString();
      final var options = PgConnectOptions.fromUri(urlToUse);
      options.setSslOptions(Objects.requireNonNullElse(options.getSslOptions(), new ClientSSLOptions()));
      return options;
    }
}
