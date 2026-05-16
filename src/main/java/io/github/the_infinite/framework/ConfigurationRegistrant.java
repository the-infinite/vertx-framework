package io.github.the_infinite.framework;

import org.jetbrains.annotations.Nullable;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.Set;

import io.github.the_infinite.framework.doc.DocumentationController;
import io.github.the_infinite.framework.env.AppEnvironment;
import io.github.the_infinite.framework.job.JobRegistry;
import io.github.the_infinite.framework.logging.console.ConsoleLogger;
import io.github.the_infinite.framework.logging.correlation.CorrelationContext;
import io.github.the_infinite.framework.queue.QueueConsumer;
import io.github.the_infinite.framework.utils.VertxValidationHelper;
import io.vertx.core.*;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.impl.future.PromiseImpl;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetServerOptions;
import io.vertx.ext.web.Router;
import lombok.Getter;

@SuppressWarnings("unused")
public class ConfigurationRegistrant {
  static final Map<Integer, HttpServer> deployedServers = new ConcurrentHashMap<>();
  static final Map<Integer, NetServer> deployedSockets = new ConcurrentHashMap<>();
  static final Map<Integer, Object> deployedConsumers = new ConcurrentHashMap<>();
  static final Map<Integer, JobRegistry> deployedWorkers = new ConcurrentHashMap<>();
  private static final Map<Vertx, ConfigurationRegistrant> instances = new ConcurrentHashMap<>();
  private static final ReadWriteLock serverLocker = new ReentrantReadWriteLock();
  private static final ReadWriteLock consumerLocker = new ReentrantReadWriteLock();
  private static final ReadWriteLock workerLocker = new ReentrantReadWriteLock();
  private static final ReadWriteLock socketLocker = new ReentrantReadWriteLock();
  private static Vertx globalVertx = null;
  final CorrelationContext context;
  @Getter
  final Router router;
  final Vertx vertx;
  final AtomicBoolean mountedHandlers;
  private final Set<Class<? extends RouteController>> controllers = ConcurrentHashMap.newKeySet();
  private ConsoleLogger console;
  private boolean loggedServer = false;
  private boolean loggedWorker = false;
  private boolean loggedConsumer = false;
  private boolean loggedSocket = false;

  ConfigurationRegistrant(Vertx vertx) {
    this.vertx = vertx;
    this.mountedHandlers = new AtomicBoolean(false);
    this.context = CorrelationContext.from(vertx.getOrCreateContext());
    this.router = Router.router(vertx);
  }

  public static Vertx global() {
    return globalVertx;
  }

  public static void setUpGlobal(Vertx vertx, @Nullable String envFile) {
    if (globalVertx == null) {
      globalVertx = vertx;
      setUp(vertx, envFile);
    }
  }

  public static Vertx vertx() {
    final var callerContext = Vertx.currentContext();

    if (callerContext != null) {
      return callerContext.owner();
    }

    if (globalVertx != null) {
      return globalVertx;
    }

    throw new IllegalStateException("No current Vertx context available.");
  }

  public static void setUp(Vertx vertx, @Nullable String envFile) throws IllegalArgumentException {
    //? If this already exists...
    if (instances.containsKey(vertx)) {
      throw new IllegalStateException("This configuration registrant has already been initialized");
    }

    //? Now, perform assignments.
    final var instance = new ConfigurationRegistrant(vertx);
    AppEnvironment.withEnvFile(vertx, envFile);
    ConsoleLogger.initialize(vertx);
    instance.console = ConsoleLogger.getInstance(vertx);
    instances.put(vertx, instance);
  }

  public static void setUp(Vertx vertx) {
    setUp(vertx, null);
  }

  public static ConfigurationRegistrant getInstance(Vertx vertx) {
    if (!instances.containsKey(vertx)) {
      throw new IllegalStateException("This configuration registrant has not been initialized");
    }

    return instances.get(vertx);
  }

  public static ConfigurationRegistrant getInstance() {
    return getInstance(vertx());
  }

  public Vertx manager() {
    return vertx;
  }

  public int totalDeployed() {
    return deployedServers.size() + deployedSockets.size() + deployedWorkers.size() + deployedConsumers.size();
  }

  public Context getContext() {
    return vertx.getOrCreateContext();
  }

  public String getServerUrl() {
    return getHostInfo(AppEnvironment.getInstance().getServerPort())[0];
  }

  public void shutdown() {
    console.warn("Shutting down the application...");

    //? Close all servers.
    deployedServers.values().forEach(HttpServer::close);
    deployedSockets.values().forEach(NetServer::close);
    deployedWorkers.values().forEach(JobRegistry::stopJobs);
    deployedConsumers.values().forEach(consumer -> {
      if (consumer instanceof QueueConsumer<?, ?> queueConsumer) {
        queueConsumer.stop();
      }
    });

    //? Clear all maps.
    deployedServers.clear();
    deployedSockets.clear();
    deployedWorkers.clear();
    deployedConsumers.clear();
    console.info("Shutdown complete.");
  }

  public void mountMiddleware(Handler<CorrelationContext> middleware) {
    router.route().handler(RouteController.wrapMiddleware(middleware));
  }

  private String[] getServerHostInfo(AppEnvironment env) {
    return getHostInfo(env.getServerPort());
  }

  private String[] getSocketHostInfo(AppEnvironment env) {
    return getHostInfo(env.getSocketPort());
  }

  private String[] getHostInfo(int port) {
    try {
      final var localHost = InetAddress.getLocalHost();
      return new String[]{"http://" + localHost.getHostName() + ":" + port, "http://" + localHost.getHostAddress() + ":" + port};
    } catch (UnknownHostException e) {
      return new String[]{"http://localhost:%d".formatted(port), "http://127.0.0.1:%d".formatted(port)};
    }
  }

  public void mountController(RouteController controller) {
    //? Auto-mount documentation controller.
    if (controllers.add(DocumentationController.class)) {
      final var docController = new DocumentationController(vertx);

      //? Mount this here.
      System.out.println();
      console.info(("Created the '\u001B[32m%s\u001B[0m' which listens on '\u001B[36m%s\u001B[0m' ").formatted(docController.getClass().getSimpleName(), docController.basePath)).await();

      //? Then do what is required here.
      docController.registerRoutes();
    }

    //? This is fine.
    if (!controllers.add(controller.getClass())) {
      return;
    }

    //? Mount this here.
    System.out.println();
    console.info("Created the '\u001B[32m%s\u001B[0m' which listens on '\u001B[36m%s\u001B[0m' ".formatted(controller.getClass().getSimpleName(), controller.basePath)).await();

    //? Then do what is required here.
    controller.registerRoutes();
  }

  public Future<HttpServer> serve() {
    final var env = AppEnvironment.getInstance();
    final var promise = new PromiseImpl<HttpServer>();

    //? First, start time timer.
    final var timer = console.time("Started HTTP Server on port " + env.getServerPort());

    //? Create the HTTP server.
    vertx.createHttpServer(new HttpServerOptions().setHandle100ContinueAutomatically(true).setPort(env.getServerPort()).setReuseAddress(true).setReusePort(true).setUseProxyProtocol(env.getServerCount() > 1)).requestHandler(router).listen(env.getServerPort()).onFailure(error -> {
      promise.fail(error);
      timer.stop();
    }).onSuccess(httpServer -> {
      //? We have deployed one more server.
      deployedServers.put(httpServer.hashCode(), httpServer);
      console.debug("Deployed HTTP server number %d".formatted(httpServer.hashCode()));

      //? If this is not running on the expected port, log a warning.
      if (httpServer.actualPort() != env.getServerPort()) {
        console.warn("Listening on a different port " + env.getServerPort());
      }

      //? If this did not deploy anything.
      if (totalDeployed() == 0) {
        console.error("The universe has imploded");
      }

      //? Log the post-deployment notices.
      if (serverLocker.writeLock().tryLock()) {
        //? If this is not logged...
        if (!loggedServer && deployedServers.size() == env.getServerCount()) {
          loggedServer = true;
          serverLocker.writeLock().unlock();

          VertxValidationHelper.schemaRepository();
              final var localhost = getServerHostInfo(env);

          //? This is fine too.
          console.info("nAPI Routes:  %d".formatted(RouteController.totalCount));
          console.info("Consumers:    %d".formatted(deployedConsumers.size()));
          console.info("HTTP Servers: %d".formatted(deployedServers.size()));
          console.info("Service Name: %s".formatted(env.getName()));
          console.info("App Version:  %s".formatted(env.getVersionCode()));
          console.info("Public URL:   %s".formatted(localhost[0]));
          console.info("Private URL:  %s".formatted(localhost[1]));
          console.info("Environment:  %s".formatted(env.getKind()));
          timer.end();
          System.out.println();
        }

        //? If this is already logged...
        else {
          timer.stop();
          serverLocker.writeLock().unlock();
        }
      }

      //? Discard the results.
      else {
        timer.stop();
      }

      //? Complete the promise with this result.
      promise.succeed(httpServer);
    });

    //? Return this for the future.
    return promise.future();
  }

  public Future<NetServer> socket() {
    final var promise = Promise.<NetServer>promise();
    final var env = AppEnvironment.getInstance();
    NetServerOptions options;

    //? For production environments.
    if (env.getKind() == AppEnvironment.EnvironmentKind.PRODUCTION) {
      options = new NetServerOptions().setSsl(true).setPort(env.getSocketPort()).setReuseAddress(true).setReusePort(true).setUseProxyProtocol(env.getServerCount() > 1);
    }

    //? For non-production environments
    else {
      options = new NetServerOptions().setSsl(false).setPort(env.getSocketPort()).setReuseAddress(true).setReusePort(true).setUseProxyProtocol(env.getServerCount() > 1);
    }

    //? Okay then.
    final var timer = console.time("Started TCP server on port " + env.getSocketPort());

    //? Create the HTTP server.
    vertx.createNetServer(options).listen(env.getSocketPort()).onFailure(error -> {
      promise.fail(error);
      timer.stop();
    }).onSuccess(netServer -> {
      //? We have deployed one more server.
      deployedSockets.put(netServer.hashCode(), netServer);

      //? If this is not running on the expected port, log a warning.
      if (netServer.actualPort() != env.getSocketPort()) {
        console.warn("Listening on a different port " + env.getSocketPort());
      }

      //? If this did not deploy anything.
      if (totalDeployed() == 0) {
        console.error("The universe has imploded");
      }

      //? Log the post-deployment notices.
      if (socketLocker.writeLock().tryLock()) {
        //? If we have not logged this yet...
        if (!loggedSocket) {
          loggedSocket = true;
          socketLocker.writeLock().unlock();

          //? Get the host information.
          final var localhost = getSocketHostInfo(env);

          //? This is fine too.
          console.info("TCP Sockets:  %d".formatted(deployedSockets.size()));
          console.info("Public URL:   %s".formatted(localhost[0]));
          console.info("Private URL:  %s".formatted(localhost[1]));
          timer.end();
          System.out.println();
        }

        //? This is another slave so unlock this.
        else {
          timer.stop();
          socketLocker.writeLock().unlock();
        }
      }

      //? Discard the results.
      else {
        timer.stop();
      }

      //? Complete the promise with this result.
      promise.succeed(netServer);
    });

    //? Complete this
    return promise.future();
  }

  public Future<JobRegistry> worker() {
    final var promise = Promise.<JobRegistry>promise();
    final var jobRegistry = JobRegistry.getInstance(vertx);
    final var time = console.time("Finished starting the job runner");

    //? Run all jobs
    jobRegistry.runJobs().onFailure(error -> {
      console.error("Failed to start job workers: %s".formatted(error.getMessage()));
      time.stop();
      promise.fail(error);
    }).onSuccess(jobs -> {
      //? Put this in.
      deployedWorkers.put(jobRegistry.hashCode(), jobRegistry);

      //? Since this is not empty...
      if (workerLocker.writeLock().tryLock()) {
        //? Since this is not logged...
        if (!loggedWorker) {
          loggedWorker = true;
          workerLocker.writeLock().unlock();
          console.info("Workers:      %d".formatted(deployedWorkers.size()));
          console.info("Total Jobs:   %s".formatted(jobRegistry.jobCount()));
          time.end();
          System.out.println();
        }

        //? If this is already logged...
        else {
          workerLocker.writeLock().unlock();
        }
      }

      promise.succeed(jobRegistry);
    });

    return promise.future();
  }

  public <T, ResultType> Future<QueueConsumer<T, ResultType>> consumer(QueueConsumer<T, ResultType> consumer) {
    final var promise = Promise.<QueueConsumer<T, ResultType>>promise();
    final var time = console.time("Started consumer " + consumer.getClass().getSimpleName());

    //? Start the consumer.
    consumer.start().onFailure(error -> {
      console.error("Failed to start consumer '%s': %s".formatted(consumer.getClass().getSimpleName(), error.getMessage()));
      time.stop();
      promise.fail(error);
    }).onSuccess(v -> {
      //? Put this in.
      deployedConsumers.put(consumer.hashCode(), consumer);

      //? Since this is not empty...
      if (consumerLocker.writeLock().tryLock()) {
        //? Since this is not logged...
        if (!loggedConsumer) {
          loggedConsumer = true;
          consumerLocker.writeLock().unlock();
          console.info("Consumers:    %d".formatted(deployedConsumers.size()));
          time.end();
          System.out.println();
        }

        //? If this is already logged...
        else {
          consumerLocker.writeLock().unlock();
        }
      }

      promise.succeed(consumer);
    });

    return promise.future();
  }
}
