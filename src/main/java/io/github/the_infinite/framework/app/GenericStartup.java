package io.github.the_infinite.framework.app;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.TimeUnit;

import io.github.the_infinite.framework.ConfigurationRegistrant;
import io.github.the_infinite.framework.data.DatabaseFactory;
import io.github.the_infinite.framework.deploy.ConsumerVerticle;
import io.github.the_infinite.framework.deploy.ServerVerticle;
import io.github.the_infinite.framework.deploy.SocketVerticle;
import io.github.the_infinite.framework.deploy.WorkerVerticle;
import io.github.the_infinite.framework.env.AppEnvironment;
import io.github.the_infinite.framework.gateway.GatewayConnect;
import io.github.the_infinite.framework.logging.console.ConsoleLogger;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;

@SuppressWarnings({"CallToPrintStackTrace", "unused"})
public final class GenericStartup {
  private GenericStartup() {
  }

  private static Vertx createVertxInstance(boolean global, StartupOptions options) {
    final var vertx = Vertx.vertx(new VertxOptions().setHAEnabled(true)
      .setMaxWorkerExecuteTime(options.workerMaxExecuteTimeMinutes()).setMaxWorkerExecuteTimeUnit(TimeUnit.MINUTES)
      .setMaxEventLoopExecuteTime(options.eventLoopMaxExecuteTimeMinutes()).setMaxEventLoopExecuteTimeUnit(TimeUnit.MINUTES)
      .setBlockedThreadCheckInterval(options.blockedThreadCheckIntervalMillis()).setBlockedThreadCheckIntervalUnit(TimeUnit.MILLISECONDS)
      .setEventLoopPoolSize(options.eventLoopPoolSize())
      .setWorkerPoolSize(options.workerPoolSize()));

    //? Register all global configurations.
    if (!global) {
      ConfigurationRegistrant.setUp(vertx);
    }

    //? If this is global, initialize the console here.
    else {
      ConfigurationRegistrant.setUpGlobal(vertx, null);
    }

    //? Do what is necessary.
    AppEnvironment.withEnvFile(vertx, null);
    DatabaseFactory.getRedisClient(vertx);

    //? Return this instance.
    return vertx;
  }

  public static void bootstrap(
    @NotNull StartupOptions options,
    @NotNull List<String> mountPaths,
    @NotNull StaticRegistrar staticRegistrar,
    @NotNull ControllerRegistrar registrar,
    @Nullable ConsumerRegistrar consumerRegistrar
  ) {
    final var cpuCount = Runtime.getRuntime().availableProcessors();
    final var staticVertx = createVertxInstance(true, options);
    final var env = AppEnvironment.getInstance();
    final var globalConsole = ConsoleLogger.getInstance(staticVertx);

    //? Now, use the static registrar to register static resources.
    staticRegistrar.registerStatic(staticVertx);

    //? Let us initialize the service configuration itself.
    try {
      GatewayConnect.setUp(staticVertx, mountPaths, options.serviceProtocol(),
        options.serviceWeight(), options.authType()).await();
      globalConsole.info("Successfully registered service configuration.");
    } catch (Exception e) {
      globalConsole.error("Failed to register service configuration: %s".formatted(e.getMessage()));
      if (env.getKind() != AppEnvironment.EnvironmentKind.PRODUCTION) {
        e.printStackTrace();
      }
    }

    //? Count the instances and compare with the CPU count. If the deployable count
    // exceeds the CPU count, we log a warning.
    final var deployableCount = env.getServerCount() + env.getSocketCount() + env.getWorkerCount() + env.getConsumerCount();

    //? This is also fine.
    if (deployableCount > cpuCount) {
      globalConsole.warn("The number of deployable (%d) exceeds the number of CPU cores (%d). This may lead to performance degradation.".formatted(deployableCount, cpuCount));
    }

    //? If the server count is not zero...
    if (env.getServerCount() > 0) {
      final var serverVertx = createVertxInstance(false, options);
      final var serverConfig = ConfigurationRegistrant.getInstance(serverVertx);
      final var controllers = registrar.mountAll(serverVertx);

      //? Then we add the controllers here.
      for (final var controller : controllers) {
        serverConfig.mountController(controller);
      }

      //? Finally, deploy this verticle.
      serverVertx.deployVerticle(ServerVerticle::new, new DeploymentOptions().setHa(true).setWorkerPoolName("Server-Pool").setWorkerPoolSize(Math.ceilDiv(cpuCount, env.getServerCount())).setInstances(env.getServerCount())).onFailure(throwable -> globalConsole.error("Failed to deploy server: %s".formatted(throwable.getMessage())));
    }

    //? If this is a worker...
    if (env.getWorkerCount() > 0) {
      //? Create a new vertx instance for this.
      final var workerVertx = createVertxInstance(false, options);

      //? Then we add the controllers here.
      workerVertx.deployVerticle(WorkerVerticle::new, new DeploymentOptions().setHa(true).setWorkerPoolName("Worker-Pool").setWorkerPoolSize(Math.ceilDiv(cpuCount, env.getWorkerCount())).setInstances(env.getWorkerCount())).onFailure(throwable -> globalConsole.error("Failed to deploy worker: %s".formatted(throwable.getMessage())));
    }

    //? If there are any consumers, we deploy them here.
    if (env.getConsumerCount() > 0 && consumerRegistrar != null) {
      final var consumerVertx = createVertxInstance(false, options);

      //? Finally, deploy this verticle.
      consumerVertx.deployVerticle(() -> new ConsumerVerticle<>(consumerRegistrar.consume(consumerVertx)),
        new DeploymentOptions().setHa(true).setWorkerPoolName("Consumer-Pool").setInstances(env.getConsumerCount())).onFailure(throwable -> globalConsole.error("Failed to deploy consumer: %s".formatted(throwable.getMessage())));
    }

    //? If this is a socket...
    if (env.getSocketCount() > 0) {
      final var socketVertx = createVertxInstance(false, options);

      //? Finally, deploy this verticle.
      socketVertx.deployVerticle(SocketVerticle::new, new DeploymentOptions().setHa(true).setWorkerPoolName("Socket-Pool").setWorkerPoolSize(Math.ceilDiv(cpuCount, env.getWorkerCount())).setInstances(env.getWorkerCount())).onFailure(throwable -> globalConsole.error("Failed to deploy socket server: %s".formatted(throwable.getMessage())));
    }
  }
}
