package io.github.the_infinite.framework.env;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import io.github.cdimascio.dotenv.Dotenv;
import io.vertx.core.Vertx;
import io.vertx.core.internal.logging.Logger;
import io.vertx.core.internal.logging.LoggerFactory;

@SuppressWarnings("unused")
public class AppEnvironment {
  @Nullable
  private static AppEnvironment instance = null;
  private static final Logger log = LoggerFactory.getLogger(AppEnvironment.class);

  // We keep the Vertx context here for use in other parts of the application.
  private final ArrayList<String> missingKeys;
  private final Map<String, String> configMap;

  // Our server configuration
  private String name;
  private String versionCode;
  private int buildNumber;

  //? For instances count
  private int serverCount;
  private int consumerCount;
  private int workerCount;
  private int socketCount;

  // For the database and service configuration.
  private String pgUrl;
  private String redisUrl;
  private String rabbitMqUrl;
  private String mongoDbUrl;
  private String esUrl;
  private String esApiKey;
  private int serverPort;
  private int socketPort;
  private int prefetchCount;
  private String url;
  private SetupMode setupMode;
  private EnvironmentKind kind;
  private ProcessRole processRole;

  private AppEnvironment(Vertx vertx) {
    this.missingKeys = new ArrayList<>();
    this.configMap = new HashMap<>();
  }

  /**
   * Gets the singleton instance of the application environment.
   */
  public static AppEnvironment getInstance() {
    if (instance == null) {
      throw new IllegalStateException("AppEnvironment has not been provisioned yet.");
    }

    //? This is fine.
    return instance;
  }

  /**
   * Helper function used to provision the application using an environment variables file.
   *
   * @param vertx The Vertx context to use.
   * @param path  The path to the environment variables file. It can be null, in which case the default .env file will be used.
   */
  public static void withEnvFile(Vertx vertx, @Nullable String path) throws IllegalArgumentException {
    if (instance != null) {
      return;
    }

    // Load environment variables from the specified file.
    instance = new AppEnvironment(vertx);

    //? If this is not supposed to be null...
    try {
      Dotenv dotenv;
      if (path != null) {
        dotenv = Dotenv.configure().filename(path).load();
      }

      //? Else, load from the default .env file.
      else {
        dotenv = Dotenv.load();
      }

      //? Okay then.
      for (var entry : dotenv.entries()) {
        instance.configMap.put(entry.getKey(), entry.getValue());
      }

      //? Okay then.
      log.info("Loaded environment variables from file: %s".formatted(Objects.requireNonNullElse(path, ".env")));
    } catch(Exception e) {
      log.info(("Failed to load environment variables from file: %s. Defaulting to system environment values.").formatted(e.getMessage()));
    }

    //? First, for the service type config settings.
    var serverName = instance.getRequired("SERVICE_NAME");
    var versionCode = instance.getRequired("SERVICE_VERSION");
    var buildNumberStr = instance.getRequired("BUILD_NUMBER");
    var envKind = instance.getRequired("ENVIRONMENT_KIND");
    var processRole = instance.get("PROCESS_ROLE", "WORKER");
    var setupMode = instance.get("SETUP_MODE", "PRODUCTION");
    var serverPort = instance.getRequired("SERVER_PORT");
    var urlStr = instance.getRequired("SERVICE_URL");

    //? Second, for the database and service config settings.
    var pgUrl = instance.getRequired("POSTGRESQL_URL");
    var redisUrl = instance.getRequired("REDIS_URL");
    var rabbitMqUrl = instance.getRequired("RABBITMQ_URL");
    var mongoDbUrl = instance.getRequired("MONGODB_URL");
    var esUrl = instance.get("ELASTICSEARCH_URL", "<none>");
    var esApiKey = instance.get("ELASTICSEARCH_API_KEY", "<none>");

    //? Third, for cluster member counts.
    var serverCount = instance.get("SERVER_COUNT", "1");
    var consumerCount = instance.get("CONSUMER_COUNT", "0");
    var workerCount = instance.get("WORKER_COUNT", "0");
    var socketCount = instance.get("SOCKET_COUNT", "0");
    var socketPort = instance.get("SOCKET_PORT", "8081");
    var prefetchCount = instance.get("PREFETCH_COUNT", "1");

    //? If missing keys were found, throw an exception.
    if (!instance.missingKeys.isEmpty()) {
      throw new IllegalStateException("Missing required environment variables: " + String.join(", ", instance.missingKeys));
    }

    //? Now, use these values as is necessary.
    instance.name = serverName;
    instance.versionCode = versionCode;

    try {
      instance.buildNumber = Integer.parseUnsignedInt(buildNumberStr);
    } catch (NumberFormatException e) {
      throw new IllegalStateException("Invalid BUILD_NUMBER value: " + buildNumberStr);
    }

    try {
      instance.serverPort = Integer.parseInt(serverPort);
    } catch (NumberFormatException e) {
      throw new IllegalStateException("Invalid SERVER_PORT value: " + serverPort);
    }


    try {
      instance.kind = EnvironmentKind.valueOf(envKind.toUpperCase());
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException("Invalid ENVIRONMENT_KIND value: " + envKind);
    }

    try {
      instance.socketPort = Integer.parseInt(socketPort);
    } catch (NumberFormatException e) {
      throw new IllegalStateException("Invalid SOCKET_PORT value: " + socketPort);
    }

    try {
      instance.socketCount = Integer.parseInt(socketCount);
    } catch (NumberFormatException e) {
      throw new IllegalStateException("Invalid SOCKET_COUNT value: " + socketCount);
    }

    try {
      instance.prefetchCount = Integer.parseInt(prefetchCount);
    } catch (NumberFormatException e) {
      throw new IllegalStateException("Invalid PREFETCH_COUNT value: " + prefetchCount);
    }

    try {
      instance.processRole = ProcessRole.valueOf(processRole.toUpperCase());
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException("Invalid PROCESS_ROLE value: " + processRole);
    }


    try {
      instance.setupMode = SetupMode.valueOf(setupMode.toUpperCase());
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException("Invalid SETUP_MODE value: " + setupMode);
    }

    instance.url = urlStr;
    instance.pgUrl = pgUrl;
    instance.redisUrl = redisUrl;
    instance.rabbitMqUrl = rabbitMqUrl;
    instance.mongoDbUrl = mongoDbUrl;
    instance.esUrl = esUrl;
    instance.esApiKey = esApiKey;


    try {
      instance.workerCount = Integer.parseInt(workerCount);
    } catch (NumberFormatException e) {
      throw new IllegalStateException("Invalid WORKER_COUNT value: " + setupMode);
    }

    try {
      instance.consumerCount = Integer.parseInt(consumerCount);
    } catch (NumberFormatException e) {
      throw new IllegalStateException("Invalid CONSUMER_COUNT value: " + setupMode);
    }

    try {
      instance.serverCount = Integer.parseInt(serverCount);
    } catch (NumberFormatException e) {
      throw new IllegalStateException("Invalid SERVER_COUNT value: " + setupMode);
    }
  }

  public String getName() {
    return name;
  }

  public int getServerCount() {
    return serverCount;
  }

  public int getConsumerCount() {
    return consumerCount;
  }

  public int getWorkerCount() {
    return workerCount;
  }

  public String getVersionCode() {
    return versionCode;
  }

  public int getBuildNumber() {
    return buildNumber;
  }

  public String getPgUrl() {
    return pgUrl;
  }

  public String getRedisUrl() {
    return redisUrl;
  }

  public String getRabbitMqUrl() {
    return rabbitMqUrl;
  }

  public String getMongoDbUrl() {
    return mongoDbUrl;
  }

  public String getEsUrl() {
    return esUrl;
  }

  public String getEsApiKey() {
    return esApiKey;
  }

  public int getServerPort() {
    return serverPort;
  }

  public int getSocketPort() {
    return socketPort;
  }

  public int getSocketCount() {
    return socketCount;
  }

  public int getPrefetchCount() {
    return prefetchCount;
  }

  public String getUrl() {
    return url;
  }

  public SetupMode getSetupMode() {
    return setupMode;
  }

  public EnvironmentKind getKind() {
    return kind;
  }

  public ProcessRole getProcessRole() {
    return processRole;
  }

  /**
   * Internal utility function to get an environment variable value and cast it
   * to the appropriate type.
   *
   * @param key          The environment variable key to look up.
   * @param defaultValue The default value to return if the key is not found.
   * @return The value of the environment variable cast to the expected type, or null if not found.
   */
  public String get(String key, @Nullable String defaultValue) {
    String value = defaultValue;

    //? Let us then do this...
    if (this.configMap.containsKey(key)) {
      value = this.configMap.get(key);
    }

    //? Try to get this from environment variables as well.
    if (System.getenv().containsKey(key)) {
      value = System.getenv(key);
    }

    //? If this was not found...
    if (value == null) {
      this.missingKeys.add(key);
    }

    return value;
  }

  /**
   * @see #get(String, String)
   */
  public String getRequired(String key) {
    String value = get(key, null);

    if (value == null) {
      throw new IllegalStateException("Missing required environment variable: " + key);
    }

    return value;
  }

  /**
   * @see #get(String, String)
   */
  public String get(String key) {
    return get(key, null);
  }

  /**
   * Gets the kind of environment this application is currently running in. Used to toggle certain features on or off
   * depending on the environment.
   */
  public enum EnvironmentKind {
    /**
     * The local development environment. This is used when running the application on a developer's machine.
     */
    DEVELOPMENT,

    /**
     * The debugging environment. This is used when running the application with debugging features enabled on a remote
     * host, however.
     */
    DEBUG,

    /**
     * The testing environment. This is used when running the application in a testing environment, such as CI/CD
     * pipelines.
     */
    STAGING,

    /**
     * The production environment. This is used when running the application in a live production environment.
     */
    PRODUCTION,
  }

  /**
   * Gets the role of this process in a distributed system. Used to determine which tasks and services should be
   * enabled or disabled.
   */
  public enum ProcessRole {
    /**
     * The purpose of this process is to perform long-running background tasks.
     */
    WORKER,

    /**
     * The purpose of this process is to handle incoming API requests from clients.
     */
    CONSUMER,

    /**
     * The purpose of this process is to serve as the main API server.
     */
    API_SERVER,

    /**
     * The purpose of this process is to set up network streams.
     */
    STREAM,
  }

  /**
   * Gets the setup mode of the application. Used to determine which features and services should be enabled or
   * disabled.
   */
  public enum SetupMode {
    /**
     * The application is running in full production mode. Therefore, all jobs, services, and features are enabled.
     */
    PRODUCTION,

    /**
     * The application is running in partial mode. Therefore, only essential jobs, services, and features are enabled.
     */
    PARTIAL,

    /**
     * The application is running in local development mode. Therefore, only local jobs, services, and features are
     * enabled.
     */
    LOCAL,
  }

  /**
   * The database SSL modes supported by the application.
   */
  public enum DatabaseSSLMode {
    DISABLE, REQUIRE, VERIFY_CA, VERIFY_FULL
  }
}
