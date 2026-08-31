package io.github.the_infinite.framework.data;

import org.apache.http.HttpHost;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.indices.GetIndexRequest;
import org.hibernate.boot.MetadataSources;
import org.hibernate.SessionFactory;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.jetbrains.annotations.NotNull;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.the_infinite.framework.data.cache.CachingStrategy;
import io.github.the_infinite.framework.data.cache.InMemoryRegionFactory;
import io.github.the_infinite.framework.data.cache.RedisRegionFactory;
import io.github.the_infinite.framework.data.seed.SeederEntry;
import io.github.the_infinite.framework.env.AppEnvironment;
import io.github.the_infinite.framework.logging.console.ConsoleLogger;
import io.github.the_infinite.framework.retry.RetryStrategy;
import io.github.the_infinite.framework.utils.DataHelpers;
import io.reactiverse.elasticsearch.client.RestHighLevelClient;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.mongo.MongoClient;
import io.vertx.rabbitmq.RabbitMQClient;
import io.vertx.rabbitmq.RabbitMQOptions;
import io.vertx.redis.client.Redis;
import io.vertx.redis.client.RedisOptions;
import io.vertx.redis.client.impl.RedisClient;

@SuppressWarnings("unused")
public final class DatabaseFactory {
  private static final PostgresOptions defaultOptions = new PostgresOptions();
  private static final Map<Vertx, RabbitMQClient> queueClients = new ConcurrentHashMap<>();
  private static final Map<String, SessionFactory> sessionFactories = new ConcurrentHashMap<>();
  private static final Map<Vertx, RestHighLevelClient> elasticClients = new ConcurrentHashMap<>();
  private static final Map<Vertx, RedisClient> redisClients = new ConcurrentHashMap<>();
  private static final Map<Vertx, MongoClient> mongoClients = new ConcurrentHashMap<>();
  private static final int DEFAULT_ES_PORT = 9200;

  //* Connection bootstrap retry policy. Core infrastructure (databases, brokers, caches) must come up;
  //* we retry with exponential back-off and jitter and then terminate the process if it never does.
  private static final long CONNECT_BASE_DELAY_MS = 1_000L;
  private static final long CONNECT_MAX_DELAY_MS = 30_000L;
  private static final int CONNECT_MAX_ATTEMPTS = 12;

  public static Future<SessionFactory> createPostgresDatabase(Vertx vertx) {
    return createPostgresDatabase(vertx, defaultOptions);
  }

  public static RedisClient getRedisClient(Vertx vertx) {
    //? If there is already a redis client for this one...
    if (redisClients.containsKey(vertx)) {
      return redisClients.get(vertx);
    }

    //? Create a new redis client.
    final var env = AppEnvironment.getInstance();
    final var console = ConsoleLogger.getInstance(vertx);
    final var client = (RedisClient) Redis.createClient(vertx, new RedisOptions().setConnectionString(env.getRedisUrl()));

    //? Connect to Redis with retry + exponential back-off + jitter. Die if we never connect.
    connectWithRetry(vertx, "Redis", () -> client.connect().mapEmpty());

    console.exec("Connected to Redis Successfully\n");
    redisClients.put(vertx, client);
    return client;
  }

  public static MongoClient getMongoClient(Vertx vertx) {
    //? If there is already a mongo client for this one...
    if (mongoClients.containsKey(vertx)) {
      return mongoClients.get(vertx);
    }

    //? Create a new mongo client.
    final var env = AppEnvironment.getInstance();
    final var console = ConsoleLogger.getInstance(vertx);
    final var config = new JsonObject()
      .put("connection_string", env.getMongoDbUrl())
      .put("useObjectId", true);
    final var client = MongoClient.createShared(vertx, config);

    //? Fetch collections to validate connectivity with retry + exponential back-off + jitter.
    connectWithRetry(vertx, "MongoDB", () -> client.getCollections().mapEmpty());

    console.exec("Connected to MongoDB Successfully\n");
    mongoClients.put(vertx, client);
    return client;
  }

  public static RabbitMQClient getQueueClient(Vertx vertx) {
    //? If there is already a queue client for this one...
    if (queueClients.containsKey(vertx)) {
      return queueClients.get(vertx);
    }

    //? Create a new queue client.
    final var env = AppEnvironment.getInstance();
    final var console = ConsoleLogger.getInstance(vertx);
    final var config = new RabbitMQOptions()
      .setAutomaticRecoveryEnabled(true)
      .setReconnectInterval(5_000)
      .setReconnectAttempts(10);
    config.setUri(env.getRabbitMqUrl());
    config.setVirtualHost(env.getRabbitMqVhost());
    final var client = RabbitMQClient.create(vertx, config);

    //? Connect to RabbitMQ with retry + exponential back-off + jitter. Die if we never connect.
    connectWithRetry(vertx, "RabbitMQ", () -> client.start().compose(v ->
      client.isConnected()
        ? Future.succeededFuture()
        : Future.failedFuture(new IllegalStateException("RabbitMQ client reported not connected after start()"))
    ));

    console.exec("Connected to RabbitMQ Successfully\n");
    queueClients.put(vertx, client);
    return client;
  }

  private static ParsedEsUrl parseEsUrl(String rawUrl) {
    String trimmed = rawUrl == null ? "" : rawUrl.trim();
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException("Elasticsearch URL is required");
    }

    String normalized = trimmed.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")
      ? trimmed
      : "http://" + trimmed;
    URI uri = URI.create(normalized);

    String scheme = uri.getScheme() == null ? "http" : uri.getScheme();
    String host = uri.getHost();
    if (host == null || host.isEmpty()) {
      throw new IllegalArgumentException("Elasticsearch host is required");
    }

    int port = uri.getPort() > 0 ? uri.getPort() : DEFAULT_ES_PORT;
    return new ParsedEsUrl(scheme, host, port);
  }

  public static RestHighLevelClient getElasticClient(Vertx vertx) {
    //? If there is already an elastic client for this one...
    if (elasticClients.containsKey(vertx)) {
      return elasticClients.get(vertx);
    }

    //? Create a new elastic client.
    final var env = AppEnvironment.getInstance();
    final var console = ConsoleLogger.getInstance(vertx);
    final var parsed = parseEsUrl(env.getEsUrl());

    final var client = RestHighLevelClient.create(
      vertx,
      RestClient.builder(new HttpHost(parsed.host(), parsed.port(), parsed.scheme()))
    );

    //? Fetch indices to validate connectivity with retry + exponential back-off + jitter.
    connectWithRetry(vertx, "Elasticsearch", () -> probeElastic(client));

    console.exec("Connected to Elasticsearch Successfully\n");
    elasticClients.put(vertx, client);
    return client;
  }

  private static Future<Void> probeElastic(RestHighLevelClient client) {
    final var promise = Promise.<Void>promise();
    try {
      client.indices().getAsync(new GetIndexRequest().humanReadable(true), RequestOptions.DEFAULT, result -> {
        if (result.failed()) {
          promise.fail(result.cause());
        } else {
          promise.complete();
        }
      });
    } catch (Exception ex) {
      promise.fail(ex);
    }
    return promise.future();
  }

  public static Future<SessionFactory> createPostgresDatabase(@NotNull Vertx vertx, @NotNull PostgresOptions options) {
    //? We need these singletons first.
    final var console = ConsoleLogger.getInstance(vertx);
    final var env = AppEnvironment.getInstance();

    //? If there is already a hit for this, return it.
    if (sessionFactories.containsKey(options.unitName)) {
      console.warn("Attempting to reinitialize a previously initialized database session factory '%s'.".formatted(options.unitName));
      return Future.succeededFuture(sessionFactories.get(options.unitName));
    }

    //? Then create and time the connection.
    final var databaseConnectTimer = console.time("Connecting to the PG database for '%s'".formatted(options.unitName));

    //? Then we log this.
    final var connectAction = (java.util.function.Supplier<Future<SessionFactory>>) () -> vertx.executeBlocking(() -> {
      //? First, build the basics.
      final var props = new HashMap<String, Object>();
      props.put("jakarta.persistence.jdbc.url", toJDBCUrl(options.url));
      props.put("jakarta.persistence.jdbc.driver", "org.postgresql.Driver");

      // Inject BOTH standard JPA and Hibernate native properties
      if (options.username != null && !options.username.isBlank()) {
        props.put("jakarta.persistence.jdbc.user", options.username);
        props.put("hibernate.connection.username", options.username);
      }
      if (options.password != null && !options.password.isBlank()) {
        props.put("jakarta.persistence.jdbc.password", options.password);
        props.put("hibernate.connection.password", options.password);
      }
      props.put("hibernate.connection.provider_class", HikariConnectionProvider.class.getName());
      props.put("hibernate.hikari.maximumPoolSize", options.poolSize);
      props.put("hibernate.hikari.connectionTimeout", 5_000L);
      props.put("jakarta.persistence.schema-generation.database.action", options.schemaGenerateAction);
      props.put("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
      props.put("jakarta.persistence.validation.factory", ValidationHelper.getInstance().factory());

      //? If this is not a production build.
      if (env.shouldLogSql()) {
        props.put("hibernate.show_sql", true);
        props.put("hibernate.format_sql", true);
        props.put("hibernate.highlight_sql", true);
      }

      //? If caching is enabled...
      if (options.cachingStrategy == CachingStrategy.REDIS) {
        props.put("hibernate.cache.use_second_level_cache", true);
        props.put("hibernate.cache.use_query_cache", true);
        props.put("hibernate.cache.region.factory_class", RedisRegionFactory.class.getName());
      } else if (options.cachingStrategy == CachingStrategy.IN_MEMORY) {
        props.put("hibernate.cache.use_second_level_cache", true);
        props.put("hibernate.cache.use_query_cache", true);
        props.put("hibernate.cache.region.factory_class", InMemoryRegionFactory.class.getName());
      }

      //? 1. Create the Standard Service Registry
      final var registry = new StandardServiceRegistryBuilder()
        .applySettings(props)
        .build();

      try {
        //? 2. Add Entities Programmatically using MetadataSources
        final var metadataSources = new MetadataSources(registry);
        final var annotatedClasses = DataHelpers.findSubclasses(BaseEntity.class);
        annotatedClasses.removeIf(cls -> cls == BaseAuditableEntity.class || cls == BaseEntity.class);
        annotatedClasses.forEach(metadataSources::addAnnotatedClass);

        //? 3. Add support for managing migrations at this point.
        metadataSources.addAnnotatedClass(MigrationEntry.class);
        metadataSources.addAnnotatedClass(SeederEntry.class);

        //? 4. Build Metadata and SessionFactory (Hikari is configured to fail fast if it cannot connect).
        final var sessionFactory = metadataSources.buildMetadata().buildSessionFactory();
        PersistentRepository.initialize(SeederEntry.Modules.SYSTEM, SeederEntry.class, sessionFactory);

        //? Put this in.
        sessionFactories.put(options.unitName, sessionFactory);

        //? Then return to the created session factory.
        return sessionFactory;
      } catch (Throwable t) {
        //? Avoid leaking the registry (and its connection pool) on a failed bootstrap.
        try {
          StandardServiceRegistryBuilder.destroy(registry);
        } catch (Throwable ignored) {
        }
        throw t;
      }
    });

    return RetryStrategy.create(vertx)
      .withBaseDelay(CONNECT_BASE_DELAY_MS)
      .withMaxDelay(CONNECT_MAX_DELAY_MS)
      .withMaxAttempts(CONNECT_MAX_ATTEMPTS)
      .onExhausted(cause -> fatal(vertx, "PostgreSQL", cause))
      .withExponentialBackoff(connectAction)
      .onSuccess(sessionFactory -> console.exec("Database client is ready\n"))
      .onSuccess(v -> databaseConnectTimer.end());
  }

  /**
   * Blocks the calling thread (bootstrap context) while retrying {@code probe} with exponential back-off and
   * jitter. If every attempt is exhausted the process is terminated via {@link #fatal(Vertx, String, Throwable)}.
   */
  private static void connectWithRetry(Vertx vertx, String component, java.util.function.Supplier<Future<Void>> probe) {
    final var future = RetryStrategy.create(vertx)
      .withBaseDelay(CONNECT_BASE_DELAY_MS)
      .withMaxDelay(CONNECT_MAX_DELAY_MS)
      .withMaxAttempts(CONNECT_MAX_ATTEMPTS)
      .onExhausted(cause -> fatal(vertx, component, cause))
      .withExponentialBackoff(probe);

    try {
      future.toCompletionStage().toCompletableFuture().join();
    } catch (Exception ignored) {
      //? fatal() has already terminated the JVM; this is purely defensive.
    }
  }

  /**
   * Terminal failure handler for core infrastructure. Logs the root cause and shuts the process down. We would
   * rather fail fast and loudly than run in a degraded state without a database/broker.
   */
  private static void fatal(Vertx vertx, String component, Throwable cause) {
    try {
      final var console = ConsoleLogger.getInstance(vertx);
      console.error("FATAL: Could not establish a connection to %s after exhausting all retry attempts. The application cannot run without it.".formatted(component));
    } catch (Throwable ignored) {
    }

    try {
      vertx.close().toCompletionStage().toCompletableFuture().get(10, java.util.concurrent.TimeUnit.SECONDS);
    } catch (Throwable ignored) {
    }

    System.exit(1);
  }

  private static String toJDBCUrl(String url) {
    String usedUrl = url.trim();

    // Vert.x and Hibernate strictly require "postgresql", not "postgres"
    if (usedUrl.startsWith("postgres://")) {
      usedUrl = usedUrl.replaceFirst("postgres://", "postgresql://");
    } else if (usedUrl.startsWith("jdbc:postgres://")) {
      usedUrl = usedUrl.replaceFirst("jdbc:postgres://", "jdbc:postgresql://");
    }

    if (usedUrl.startsWith("jdbc:")) {
      return usedUrl;
    }
    return "jdbc:%s".formatted(usedUrl);
  }

  private record ParsedEsUrl(String scheme, String host, int port) {
  }

  public static class PostgresOptions {
    private int poolSize;
    private String schemaGenerateAction;
    private String unitName;
    private String url;
    private CachingStrategy cachingStrategy;
    private String username;
    private String password;

    public PostgresOptions() {
      final var env = AppEnvironment.getInstance();
      this.poolSize = 10;
      this.cachingStrategy = CachingStrategy.REDIS;
      this.schemaGenerateAction = "none";
      this.unitName = "default-pg-instance";

      // Parse the URL from the environment and extract credentials
      parseAndSetUrl(env.getPgUrl());
    }

    private void parseAndSetUrl(String rawUrl) {
      final var resolved = ConnectionResolver.resolveUri(rawUrl);

      resolved.ifPresent(info -> {
        this.password = info.password();
        this.username = info.username();
        this.url = info.url();
      });
    }

    public PostgresOptions setCachingStrategy(CachingStrategy cachingEnabled) {
      this.cachingStrategy = cachingEnabled;
      return this;
    }

    public PostgresOptions setPoolSize(short poolSize) {
      this.poolSize = poolSize;
      return this;
    }

    public PostgresOptions setSchemaGenerateAction(String generateAction) {
      this.schemaGenerateAction = generateAction;
      return this;
    }

    public PostgresOptions setOverrideUrl(String url) {
      parseAndSetUrl(url); // Reparse if manually overridden
      return this;
    }

    public PostgresOptions setUnitName(String unitName) {
      this.unitName = unitName;
      return this;
    }

    public PostgresOptions setUsername(String username) {
      this.username = username;
      return this;
    }

    public PostgresOptions setPassword(String password) {
      this.password = password;
      return this;
    }
  }
}
