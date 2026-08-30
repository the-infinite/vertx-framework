package io.github.the_infinite.framework.data;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.hibernate.engine.jdbc.connections.spi.ConnectionProvider;
import org.hibernate.service.spi.Configurable;
import org.hibernate.service.spi.Stoppable;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

/**
 * A bounded, timeout-aware JDBC connection provider backed by HikariCP.
 * <p>
 * This replaces Hibernate's built-in driver-manager pool (configured via
 * {@code hibernate.connection.pool_size}), which blocks indefinitely (with no timeout) whenever
 * every connection is checked out. Combined with lazy initialization running on the Vert.x event
 * loop, that indefinite block caused the application to appear to hang completely.
 * <p>
 * HikariCP enforces a hard {@code connectionTimeout}, so any exhausted-pool situation now surfaces
 * as a fast, clear error instead of a permanent stall.
 */
public final class HikariConnectionProvider implements ConnectionProvider, Configurable, Stoppable {
  private static final String JDBC_URL = "jakarta.persistence.jdbc.url";
  private static final String JDBC_USER = "jakarta.persistence.jdbc.user";
  private static final String JDBC_PASSWORD = "jakarta.persistence.jdbc.password";
  private static final String MAXIMUM_POOL_SIZE = "hibernate.hikari.maximumPoolSize";
  private static final String CONNECTION_TIMEOUT = "hibernate.hikari.connectionTimeout";
  private static final String INITIALIZATION_FAIL_TIMEOUT = "hibernate.hikari.initializationFailTimeout";

  private static final int DEFAULT_MAXIMUM_POOL_SIZE = 10;
  private static final long DEFAULT_CONNECTION_TIMEOUT_MS = 5_000L;
  //? Fail fast at bootstrap so the framework's connection retry / "die on exhaustion" policy can take over,
  //? instead of the pool silently starting in a broken state.
  private static final long DEFAULT_INITIALIZATION_FAIL_TIMEOUT_MS = 5_000L;

  private HikariDataSource dataSource;

  @Override
  public void configure(Map<String, Object> configurationValues) {
    final var config = new HikariConfig();
    config.setPoolName("hibernate");
    config.setJdbcUrl((String) configurationValues.get(JDBC_URL));
    config.setUsername((String) configurationValues.get(JDBC_USER));
    config.setPassword((String) configurationValues.get(JDBC_PASSWORD));
    config.setMaximumPoolSize(intSetting(configurationValues));
    config.setConnectionTimeout(longSetting(configurationValues));
    config.setInitializationFailTimeout(initializationFailTimeout(configurationValues));
    config.setValidationTimeout(2_000L);
    config.setIdleTimeout(600_000L);
    config.setMaxLifetime(1_800_000L);
    this.dataSource = new HikariDataSource(config);
  }

  @Override
  public Connection getConnection() throws SQLException {
    return dataSource.getConnection();
  }

  @Override
  public void closeConnection(Connection connection) throws SQLException {
    connection.close();
  }

  @Override
  public boolean supportsAggressiveRelease() {
    return false;
  }

  @Override
  public <T> T unwrap(Class<T> unwrapType) {
    if (unwrapType.isInstance(dataSource)) {
      return unwrapType.cast(dataSource);
    }
    return null;
  }

  @Override
  public boolean isUnwrappableAs(Class<?> unwrapType) {
    return unwrapType.isInstance(dataSource);
  }

  @Override
  public void stop() {
    if (dataSource != null) {
      dataSource.close();
    }
  }

  private static int intSetting(Map<String, Object> values) {
    final var raw = values.get(HikariConnectionProvider.MAXIMUM_POOL_SIZE);
    return raw == null ? HikariConnectionProvider.DEFAULT_MAXIMUM_POOL_SIZE : Integer.parseInt(String.valueOf(raw));
  }

  private static long longSetting(Map<String, Object> values) {
    final var raw = values.get(HikariConnectionProvider.CONNECTION_TIMEOUT);
    return raw == null ? HikariConnectionProvider.DEFAULT_CONNECTION_TIMEOUT_MS : Long.parseLong(String.valueOf(raw));
  }

  private static long initializationFailTimeout(Map<String, Object> values) {
    final var raw = values.get(HikariConnectionProvider.INITIALIZATION_FAIL_TIMEOUT);
    return raw == null ? HikariConnectionProvider.DEFAULT_INITIALIZATION_FAIL_TIMEOUT_MS : Long.parseLong(String.valueOf(raw));
  }
}
