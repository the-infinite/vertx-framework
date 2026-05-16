package io.github.the_infinite.framework;

import org.hibernate.reactive.mutiny.Mutiny;
import org.testcontainers.containers.PostgreSQLContainer;

import io.github.the_infinite.framework.data.DatabaseFactory;
import io.github.the_infinite.framework.logging.console.ConsoleLogger;
import io.github.the_infinite.framework.response.ErrorResult;
import io.vertx.core.Future;
import io.vertx.core.Vertx;

@SuppressWarnings({"unused", "resource"})
public class AppConfig {
  private static Future<Mutiny.SessionFactory> databaseSession;

  private final Vertx vertx;

  public AppConfig(Vertx vertx) {
    this.vertx = vertx;
  }

  public Future<Mutiny.SessionFactory> createAppDatabase() {
    //return DatabaseFactory.createPostgresDatabase(vertx);
    return DatabaseFactory.createPostgresDatabase(vertx, new DatabaseFactory.PostgresOptions().setOverrideUrl(startDatabaseContainer()));
  }

  private String startDatabaseContainer() {
    final var console = ConsoleLogger.getInstance(vertx);
    try (
      final var postgreSQLContainer = new PostgreSQLContainer<>("postgres:18-alpine")
        .withDatabaseName("postgres")
        .withUsername("postgres")
        .withPassword("vertx-in-action")
    ) {
      postgreSQLContainer.start();
      return postgreSQLContainer.getJdbcUrl();
    } catch (Throwable t) {
      final var error = ErrorResult.of(t);
      console.error(error.getMessage());
      return "";
    }
  }
}
