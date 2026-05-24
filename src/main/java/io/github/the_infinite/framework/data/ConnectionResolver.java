package io.github.the_infinite.framework.data;

import org.hibernate.reactive.pool.impl.DefaultSqlClientPoolConfiguration;

import java.net.URI;
import java.util.Objects;
import java.util.Optional;

import io.github.the_infinite.framework.utils.DataHelpers;
import io.vertx.core.net.ClientSSLOptions;
import io.vertx.pgclient.PgConnectOptions;

public final class ConnectionResolver extends DefaultSqlClientPoolConfiguration {
  static Optional<ConnectionInfo> resolveUri(String rawUrl) {
    if (rawUrl == null || rawUrl.isBlank()) {
      return Optional.empty();
    }

    String url = rawUrl.trim();
    String password = "";
    String username = "";

    try {
      // Standardize scheme early so java.net.URI doesn't get confused
      String parsableUrl = rawUrl.replaceFirst("^jdbc:postgres(ql)?://", "postgresql://")
        .replaceFirst("^postgres://", "postgresql://");
      URI uri = URI.create(parsableUrl);

      // 1. Check for standard URI notation: username:password@host
      String userInfo = uri.getUserInfo();
      String query = uri.getQuery();

      if (userInfo != null && userInfo.contains(":")) {
        String[] parts = userInfo.split(":", 2);
        username = parts[0];
        password = parts[1];
      }

      // 2. Check for query parameters ?user=...&password=...
      else if (query != null) {
        final var queries = DataHelpers.extractQueryParams(parsableUrl);

        final var usernameValues = queries.get("user") != null ? queries.get("user") :
          queries.get("username");
        final var passwordValues = queries.get("password");

        if (usernameValues != null && !usernameValues.isEmpty()) {
          username = usernameValues.getFirst();
        }

        if (passwordValues != null && !passwordValues.isEmpty()) {
          password = passwordValues.getFirst();
        }
      }
    } catch (Exception e) {
      url = rawUrl;
    }

    return Optional.of(new ConnectionInfo(url, username, password));
  }

  @Override
  public PgConnectOptions connectOptions(URI uri) {
    final String overrideUrl = System.getProperty("pg.url.override");
    String urlToUse = overrideUrl != null ? overrideUrl : uri.toString();

    //? Resolve the URL then.
    final var resolved = resolveUri(urlToUse);

    //? If this is okay...
    if (resolved.isEmpty()) {
      throw new IllegalArgumentException("Invalid PostgreSQL URL: " + urlToUse);
    }

    //? Okay then.
    final var info = resolved.get();

    //? Replace the URL with the resolved one.
    urlToUse = info.url();

    // Vert.x's PgConnectOptions cannot parse "jdbc:" URIs.
    // We must strip it so it can correctly extract the embedded username/password.
    if (urlToUse.startsWith("jdbc:")) {
      urlToUse = urlToUse.replaceFirst("jdbc:", ""); // Removes "jdbc:"
    }

    final var options = PgConnectOptions.fromUri(urlToUse).setUser(info.username).setPassword(info.password);
    options.setSslOptions(Objects.requireNonNullElse(options.getSslOptions(), new ClientSSLOptions()));

    return options;
  }

  record ConnectionInfo(String url, String username, String password) {
  }
}
