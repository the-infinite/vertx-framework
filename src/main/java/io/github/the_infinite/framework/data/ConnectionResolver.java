package io.github.the_infinite.framework.data;

import java.net.URI;
import java.util.Optional;

import io.github.the_infinite.framework.utils.DataHelpers;

public final class ConnectionResolver {
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

  record ConnectionInfo(String url, String username, String password) {
  }
}
