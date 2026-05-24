package io.github.the_infinite.framework.data;

import org.apache.http.HttpHost;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.indices.GetIndexRequest;
import org.hibernate.boot.MetadataSources;
import org.hibernate.reactive.mutiny.Mutiny;
import org.hibernate.reactive.provider.ReactiveServiceRegistryBuilder;
import org.jetbrains.annotations.NotNull;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.the_infinite.framework.env.AppEnvironment;
import io.github.the_infinite.framework.logging.console.ConsoleLogger;
import io.github.the_infinite.framework.response.ErrorResult;
import io.github.the_infinite.framework.utils.ValidationHelper;
import io.reactiverse.elasticsearch.client.RestHighLevelClient;
import io.vertx.core.Future;
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
  private static final Map<String, Mutiny.SessionFactory> sessionFactories = new ConcurrentHashMap<>();
  private static final Map<Vertx, RestHighLevelClient> elasticClients = new ConcurrentHashMap<>();
  private static final Map<Vertx, RedisClient> redisClients = new ConcurrentHashMap<>();
  private static final Map<Vertx, MongoClient> mongoClients = new ConcurrentHashMap<>();
  private static final int DEFAULT_ES_PORT = 9200;

  public static Future<Mutiny.SessionFactory> createPostgresDatabase(Vertx vertx) {
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

    //? Connect to Redis first.
    client.connect().andThen(connectResult -> {
      if (connectResult.failed()) {
        final var error = ErrorResult.of(connectResult.cause());
        console.error("Could not connect to Redis: %s%n".formatted(error.getMessage()));
        return;
      }

      final var connection = connectResult.result();
      connection.close();
      console.exec("Connected to Redis Successfully\n");
    }).await();

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

    //? Fetch collections to validate connectivity.
    client.getCollections().onComplete(getResult -> {
      if (getResult.failed()) {
        final var error = ErrorResult.of(getResult.cause());
        console.error("Could not connect to MongoDB: %s%n".formatted(error.getMessage()));
        return;
      }

      console.exec("Connected to MongoDB Successfully\n");
    });

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
      .setReconnectInterval(5000)
      .setReconnectAttempts(10);
    config.setUri(env.getRabbitMqUrl());
    final var client = RabbitMQClient.create(vertx, config);

    //? Connect to RabbitMQ first.
    client.start().andThen(startResult -> {
      if (startResult.failed()) {
        final var error = ErrorResult.of(startResult.cause());
        console.error("Could not connect to RabbitMQ: %s%n".formatted(error.getMessage()));
        return;
      }

      console.exec("Connected to RabbitMQ Successfully\n");
    }).await();

    //? This is fine.
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

    //? Fetch indices to validate connectivity.
    try {
      client.indices().getAsync(new GetIndexRequest().humanReadable(true), RequestOptions.DEFAULT, getResult -> {
        if (getResult.failed()) {
          final var error = ErrorResult.of(getResult.cause());
          console.error("Could not connect to Elasticsearch: %s%n".formatted(error.getMessage()));
          return;
        }

        console.exec("Connected to Elasticsearch Successfully\n");
      });
    } catch (Exception ex) {
      final var error = ErrorResult.of(ex);
      console.error("Could not connect to Elasticsearch: %s%n".formatted(error.getMessage()));
    }

    //? This is fine.
    elasticClients.put(vertx, client);
    return client;
  }

  public static Future<Mutiny.SessionFactory> createPostgresDatabase(@NotNull Vertx vertx, @NotNull PostgresOptions options) {
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
    return vertx.executeBlocking(() -> {
      //? First, build the basics.
      final var props = new HashMap<String, Object>();
      props.put("jakarta.persistence.jdbc.url", toJDBCUrl(options.url));

      // Inject BOTH standard JPA and Hibernate native properties
      if (options.username != null && !options.username.isBlank()) {
        props.put("jakarta.persistence.jdbc.user", options.username);
        props.put("hibernate.connection.username", options.username);
      }
      if (options.password != null && !options.password.isBlank()) {
        props.put("jakarta.persistence.jdbc.password", options.password);
        props.put("hibernate.connection.password", options.password);
      }

      props.put("hibernate.connection.pool_size", options.poolSize);
      props.put("jakarta.persistence.schema-generation.database.action", options.schemaGenerateAction);
      props.put("hibernate.vertx.pool.configuration_class", ConnectionResolver.class.getName());
      props.put("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
      props.put("jakarta.persistence.validation.factory", ValidationHelper.getInstance().factory());

      //? If this is not a production build.
      if (env.getKind() != AppEnvironment.EnvironmentKind.PRODUCTION) {
        props.put("hibernate.show_sql", true);
        props.put("hibernate.format_sql", true);
        props.put("hibernate.highlight_sql", true);
      }

      //? 1. Create the Standard Service Registry
      final var registry = new ReactiveServiceRegistryBuilder()
        .applySettings(props)
        .build();

      //? 2. Add Entities Programmatically using MetadataSources
      final var metadataSources = new MetadataSources(registry);
      BaseEntity.ENTITY_CLASSES.forEach(metadataSources::addAnnotatedClass);

      //? 3. Build Metadata and SessionFactory
      final var sessionFactory = metadataSources.buildMetadata().buildSessionFactory();
      final var mutinyFactory = sessionFactory.unwrap(Mutiny.SessionFactory.class);

      //? Put this in.
      sessionFactories.put(options.unitName, mutinyFactory);

      //? Then return to the created mutiny factory.
      return mutinyFactory;
    }).onSuccess(sessionFactory -> console.exec("Database client is ready\n"));
  }

  private static String toJDBCUrl(String url) {
    final var usedUrl = url.trim();
    if (usedUrl.startsWith("jdbc:")) {
      return url;
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
    private String username;
    private String password;

    public PostgresOptions() {
      final var env = AppEnvironment.getInstance();
      this.poolSize = 10;
      this.schemaGenerateAction = "none";
      this.unitName = "default-pg-instance";

      // Parse the URL from the environment and extract credentials
      parseAndSetUrl(env.getPgUrl());
    }

    private void parseAndSetUrl(String rawUrl) {
      if (rawUrl == null || rawUrl.isBlank()) {
        this.url = rawUrl;
        return;
      }

      try {
        // Temporarily remove "jdbc:" so java.net.URI can parse the structure correctly
        String parsableUrl = rawUrl.startsWith("jdbc:") ? rawUrl.substring(5) : rawUrl;
        URI uri = URI.create(parsableUrl);

        // 1. Check for standard URI notation: username:password@host
        String userInfo = uri.getUserInfo();
        if (userInfo != null && userInfo.contains(":")) {
          String[] parts = userInfo.split(":", 2);
          this.username = parts[0];
          this.password = parts[1];

          // Strip the credentials from the URL so the JDBC driver doesn't get confused
          this.url = rawUrl.replace(userInfo + "@", "");
          return;
        }

        // 2. Check for query parameters: user=...&password=...
        String query = uri.getQuery();
        if (query != null) {
          String[] pairs = query.split("&");
          for (String pair : pairs) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
              if (kv[0].equalsIgnoreCase("user") || kv[0].equalsIgnoreCase("username")) {
                this.username = kv[1];
              } else if (kv[0].equalsIgnoreCase("password")) {
                this.password = kv[1];
              }
            }
          }
        }

        // Keep the original URL if credentials were only in query params (JDBC can usually handle those)
        this.url = rawUrl;

      } catch (Exception e) {
        // Fallback to the raw URL if URI parsing fails for any reason
        this.url = rawUrl;
      }
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
