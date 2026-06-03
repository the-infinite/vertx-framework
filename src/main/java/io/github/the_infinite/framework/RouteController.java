package io.github.the_infinite.framework;


import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;

import io.github.the_infinite.framework.doc.DocumentationRegistrant;
import io.github.the_infinite.framework.doc.RouteDescription;
import io.github.the_infinite.framework.env.AppEnvironment;
import io.github.the_infinite.framework.logging.console.ConsoleLogger;
import io.github.the_infinite.framework.logging.correlation.CorrelationContext;
import io.github.the_infinite.framework.middleware.GeneralMiddlewares;
import io.github.the_infinite.framework.middleware.rate.LimiterFactory;
import io.github.the_infinite.framework.middleware.rate.RateLimiter;
import io.github.the_infinite.framework.response.ErrorResult;
import io.github.the_infinite.framework.response.TypedServiceResult;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.StaticHandler;
import io.vertx.ext.web.validation.builder.Bodies;
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder;
import io.vertx.json.schema.SchemaRepository;
import io.vertx.json.schema.common.dsl.Schemas;

@SuppressWarnings({"unused", "CallToPrintStackTrace"})
public abstract class RouteController {
  public static final String REQUEST_ID = "App.RequestID";
  private static final Logger logger = LoggerFactory.getLogger(RouteController.class);
  static int totalCount = 0;
  private static RateLimiter limiter;
  protected final SchemaRepository schemaRepository;
  protected final String basePath;
  protected final ConfigurationRegistrant registrant;
  private final int version;

  public RouteController(@NotNull Vertx vertx, @NotNull String basePath, int version) {
    this.basePath = noTrailingSlash(basePath);
    this.version = version;
    this.registrant = ConfigurationRegistrant.getInstance(vertx);
    this.schemaRepository = registrant.getSchemaRepository();

    //? if this registrant has not mounted error handlers yet...
    if (registrant.mountedHandlers.compareAndSet(false, true)) {
      //? The middleware route mounts
      final var middlewareRoute = registrant.router.route();

      //? Absorb the correlation ID as needed.
      middlewareRoute.handler(wrapMiddleware(GeneralMiddlewares.correlationIdExtractor()));

      //? Mount this one too.
      middlewareRoute.handler(wrapMiddleware(GeneralMiddlewares.paginationParamsExtractor()));

      //? Then mount the error handlers.
      registrant.router.errorHandler(404, context -> {
        final var errorResult = new ErrorResult("The requested resource was not" + " found", context.request().path(), 404);
        final var correlationContext = CorrelationContext.from(context);
        endAs(errorResult.toServiceResult(), correlationContext);
      });

      //? Then mount handler 400.
      registrant.router.errorHandler(400, context -> {
        final var errorResult = ErrorResult.of(context.failure());
        final var correlationContext = CorrelationContext.from(context);
        endAs(errorResult.toServiceResult(), correlationContext);
      });

      //? Okay then.
      registrant.router.errorHandler(500, context -> {
        final var errorResult = ErrorResult.of(context.failure());
        final var correlationContext = CorrelationContext.from(context);
        endAs(errorResult.toServiceResult(), correlationContext);
      });
    }
  }

  public RouteController(@NotNull Vertx vertx, String basePath) {
    this(vertx, basePath, 0);
  }

  static Handler<RoutingContext> wrapMiddleware(Handler<CorrelationContext> middleware) {
    final var env = AppEnvironment.getInstance();
    return routingContext -> {
      try {
        final var correlationContext = CorrelationContext.from(routingContext);
        middleware.handle(correlationContext);
      } catch (Exception e) {
        final var errorResult = ErrorResult.of(e);
        ConsoleLogger.getInstance().error(errorResult.getMessage());
        if (env.getKind() != AppEnvironment.EnvironmentKind.PRODUCTION) {
          errorResult.printStackTrace();
        }
        endAs(errorResult.toServiceResult(), CorrelationContext.from(routingContext));
      }
    };
  }

  static private String extractResultBody(TypedServiceResult<?> result) {
    try {
      return result.serialize();
    } catch (Exception e) {
      final var errorResult = ErrorResult.of(e);
      ConsoleLogger.getInstance().error(errorResult.getMessage());
      if (AppEnvironment.getInstance().getKind() != AppEnvironment.EnvironmentKind.PRODUCTION) {
        if (e.getCause() != null) {
          e.getCause().printStackTrace();
        } else {
          e.printStackTrace();
        }
      }
      return errorResult.getMessage();
    }
  }

  static private void endAs(TypedServiceResult<?> result, CorrelationContext context) {
    final var routingContext = context.router();
    final var resultBody = extractResultBody(result);
    final var response = routingContext.response();

    //? If this has been sent or is no longer needed...
    if (response.headWritten() || response.ended()) {
      return;
    }

    //? Set the status code.
    response.setStatusCode(result.getCode());

    //? Add the request ID.
    response.putHeader("X-Correlation-ID", context.correlationId());

    //? If this is a JSON object.
    if (resultBody == null) {
      final var error = new IllegalStateException("Response body is null");
      response.putHeader("Content-Type", "text/plain").setStatusCode(503).end(ErrorResult.of(error).toServiceResult().getMessage());
      throw error;
    }

    //? If this is a JSON response
    if (result.getResponseType() == TypedServiceResult.ResponseType.JSON) {
      response.putHeader("Content-Type", "application/json");
      response.end(resultBody);
      return;
    }

    if (result.getResponseType() == TypedServiceResult.ResponseType.FILE) {
      response.putHeader("Content-Disposition", "attachment; filename=\"%s\"".formatted(resultBody.substring(resultBody.lastIndexOf("/") + 1)));
      response.sendFile(resultBody);
      return;
    }

    if (result.getResponseType() == TypedServiceResult.ResponseType.XML) {
      response.putHeader("Content-Type", "application/xml");
      response.end(resultBody);
      return;
    }

    if (result.getResponseType() == TypedServiceResult.ResponseType.JAVASCRIPT) {
      response.putHeader("Content-Type", "application/javascript");
      response.end(resultBody);
      return;
    }

    if (result.getResponseType() == TypedServiceResult.ResponseType.HTML) {
      response.putHeader("Content-Type", "text/html");
      response.end(resultBody);
      return;
    }

    //? Default to plain text.
    response.putHeader("Content-Type", "text/plain");
    response.end(resultBody);
  }

  static private <T> Handler<RoutingContext> wrapHandler(RouteHandler<T> handler) {
    return routingContext -> {
      final var env = AppEnvironment.getInstance();
      final var correlationContext = CorrelationContext.from(routingContext);
      correlationContext.set(REQUEST_ID, new String(new SecureRandom().generateSeed(Long.SIZE)));
      try {
        final var promise = Promise.<TypedServiceResult<T>>promise();
        final var future = promise.future();

        //? Attach the listener of sorts...
        future.andThen(typedResult -> {
          //? If this failed...
          if (!typedResult.succeeded()) {
            endAs(ErrorResult.of(typedResult.cause()).toServiceResult(), correlationContext);
            return;
          }

          //? Get the result down.
          endAs(typedResult.result(), correlationContext);
        });

        //? Then call the handler so the attached listener can propagate as required.
        handler.handle(correlationContext, promise);
      } catch (Exception e) {
        ErrorResult errorResult;

        if (e instanceof ErrorResult err) {
          errorResult = err;
        } else {
          errorResult = ErrorResult.of(e);
        }

        try {
          endAs(errorResult.toServiceResult(), correlationContext);
        } catch (Exception ignored) {
          ConsoleLogger.getInstance().error(errorResult.getMessage());
          if (env.getKind() != AppEnvironment.EnvironmentKind.PRODUCTION) {
            errorResult.printStackTrace();
          }
        }
      }
    };
  }

  private String noTrailingSlash(String path) {
    path = path.trim().replaceAll("//+", "/");

    if (path.isEmpty() || path.equals("/")) {
      return "/";
    }

    if (path.endsWith("/")) {
      return path.substring(0, path.lastIndexOf('/'));
    }

    return path;
  }


  private String calculateFullPath(String path) {
    if (version == 0) {
      return noTrailingSlash("/%s/%s".formatted(this.basePath, path));
    }
    return noTrailingSlash("/v%d/%s/%s".formatted(this.version, this.basePath, path));
  }

  private Handler<CorrelationContext> makeRateLimiterOf(RouteDescription description) {
    if (limiter == null) {
      limiter = LimiterFactory.create(registrant.manager());
    }
    return GeneralMiddlewares.rateLimiter(limiter, description.rateLimit());
  }


  @SafeVarargs
  private <T> void mount(@NotNull String path, boolean hasBody,
                         @NotNull HttpMethod method, @NotNull RouteDescription description, RouteHandler<
      T> handler, Handler<CorrelationContext>... middlewares) {
    final String fullPath = calculateFullPath(path);

    DocumentationRegistrant.getInstance().registerRoute(fullPath, method.name(), this.getClass().getSimpleName(), description);
    final var route = registrant.router.route().method(method).path(fullPath);


    //? 1. Handle Body Processing & Validation Safely
    if (hasBody) {
      final Class<?> expectedClass = description.requestBodyClass();

      if (expectedClass == null) {
        throw new IllegalArgumentException("Request body class must be specified in the route description.");
      }

      if (expectedClass != Void.class) {
        // MUST mount BodyHandler first. Vert.x will not read the HTTP buffer into memory without this.
        route.handler(BodyHandler.create());

        //? Okay then.
        final var builder = ValidationHandlerBuilder.create(schemaRepository);

        // If expecting a raw String, enforce text/plain. We don't add strict body schemas
        // here; Vert.x will just pass the raw buffer through safely
        if (expectedClass == String.class) {
          route.consumes("text/plain");
        }

        // If expecting a POJO, we seamlessly accept JSON, Form-Encoded, and Multipart!
        // Vert.x Validation Handler will parse and normalize all three into a JsonObject.
        else {
          final var genericSchema = Schemas.schema();
          final var objectSchema = Schemas.objectSchema();

          builder.body(Bodies.json(genericSchema))
            .body(Bodies.formUrlEncoded(objectSchema))
            .body(Bodies.multipartFormData(objectSchema));
        }

        // D. Build and attach the validator
        route.handler(builder.build());
      }
    }

    //? 2. Mount all custom middlewares
    if (middlewares != null) {
      for (final var middleware : middlewares) {
        if (middleware == null) continue;
        route.handler(wrapMiddleware(middleware));
      }
    }

    //? 3. If this actually has a rate limit...
    if (description.rateLimit() != null && description.rateLimit() > 0) {
      route.handler(wrapMiddleware(makeRateLimiterOf(description)));
    }

    //? 4. Mount the actual business logic handler
    route.handler(wrapHandler(handler));

    //? 5. Now, mount and log it.
    registrant.vertx.executeBlocking(() -> {
      logger.atInfo()
        .addKeyValue("handler", getClass().getSimpleName())
        .addKeyValue("method", method.name())
        .addKeyValue("path", fullPath)
        .log("Mounted a '\u001B[32m{}\u001B[0m' handler which listens on  '\u001B[36m{}\u001B[0m'", method.name(), fullPath);
      return null;
    }).await();

    //? Increment counter.
    totalCount++;
  }


  @SafeVarargs
  protected final <T> void mountGet(String path, @NotNull RouteDescription
    description, RouteHandler<T> handler, @Nullable final Handler<CorrelationContext>...
                                      middlewares) {
    mount(path, false, HttpMethod.GET, description, handler, middlewares);
  }


  @SafeVarargs
  protected final <T> void mountPost(String path, @NotNull RouteDescription
    description, RouteHandler<T> handler, @Nullable final Handler<CorrelationContext>...
                                       middlewares) {
    mount(path, true, HttpMethod.POST, description, handler, middlewares);
  }


  @SafeVarargs
  protected final <T> void mountPut(String path, @NotNull RouteDescription
    description, RouteHandler<T> handler, @Nullable final Handler<CorrelationContext>...
                                      middlewares) {
    mount(path, true, HttpMethod.PUT, description, handler, middlewares);
  }


  @SafeVarargs
  protected final <T> void mountPatch(String path, boolean hasBody,
                                      @NotNull RouteDescription description, RouteHandler<T> handler,
                                      @Nullable final Handler<CorrelationContext>... middlewares) {
    mount(path, hasBody, HttpMethod.PATCH, description, handler, middlewares);
  }


  @SafeVarargs
  protected final <T> void mountDelete(String path, @NotNull RouteDescription
    description, RouteHandler<T> handler, @Nullable final Handler<CorrelationContext>...
                                         middlewares) {
    mount(path, false, HttpMethod.DELETE, description, handler, middlewares);
  }


  @SafeVarargs
  protected final <T> void mountOptions(String path, @NotNull RouteDescription
    description, RouteHandler<T> handler, @Nullable final Handler<CorrelationContext>...
                                          middlewares) {
    mount(path, false, HttpMethod.OPTIONS, description, handler, middlewares);
  }


  @SafeVarargs
  protected final <T> void mountHead(String path, @NotNull RouteDescription
    description, RouteHandler<T> handler, @Nullable final Handler<CorrelationContext>...
                                       middlewares) {
    mount(path, false, HttpMethod.HEAD, description, handler, middlewares);
  }


  @SafeVarargs
  protected final <T> void mountTrace(String path, @NotNull RouteDescription
    description, RouteHandler<T> handler, @Nullable final Handler<CorrelationContext>...
                                        middlewares) {
    mount(path, false, HttpMethod.TRACE, description, handler, middlewares);
  }


  @SafeVarargs
  protected final <T> void mountConnect(String path, @NotNull RouteDescription
    description, RouteHandler<T> handler, @Nullable final Handler<CorrelationContext>...
                                          middlewares) {
    mount(path, false, HttpMethod.CONNECT, description, handler, middlewares);
  }


  @SafeVarargs
  protected final <T> void mountCopy(String path, @NotNull RouteDescription
    description, RouteHandler<T> handler, @Nullable final Handler<CorrelationContext>...
                                       middlewares) {
    mount(path, false, HttpMethod.COPY, description, handler, middlewares);
  }


  @SafeVarargs
  protected final <T> void mountMove(String path, @NotNull RouteDescription
    description, RouteHandler<T> handler, @Nullable final Handler<CorrelationContext>...
                                       middlewares) {
    mount(path, false, HttpMethod.MOVE, description, handler, middlewares);
  }


  @SafeVarargs
  protected final <T> void mountLock(String path, @NotNull RouteDescription
    description, RouteHandler<T> handler, @Nullable final Handler<CorrelationContext>...
                                       middlewares) {
    mount(path, true, HttpMethod.LOCK, description, handler, middlewares);
  }


  @SafeVarargs
  protected final <T> void mountUnlock(String path, @NotNull RouteDescription
    description, RouteHandler<T> handler, @Nullable final Handler<CorrelationContext>...
                                         middlewares) {
    mount(path, false, HttpMethod.UNLOCK, description, handler, middlewares);
  }


  @SafeVarargs
  protected final <T> void mountPropfind(String path, @NotNull RouteDescription
    description, RouteHandler<T> handler, @Nullable final Handler<CorrelationContext>...
                                           middlewares) {
    mount(path, true, HttpMethod.PROPFIND, description, handler, middlewares);
  }


  @SafeVarargs
  protected final <T> void mountMkcol(String path, @NotNull RouteDescription
    description, RouteHandler<T> handler, @Nullable final Handler<CorrelationContext>...
                                        middlewares) {
    mount(path, false, HttpMethod.MKCOL, description, handler, middlewares);
  }


  @SafeVarargs
  protected final <T> void mountSearch(String path, @NotNull RouteDescription
    description, RouteHandler<T> handler, @Nullable final Handler<CorrelationContext>...
                                         middlewares) {
    mount(path, true, HttpMethod.SEARCH, description, handler, middlewares);
  }


  @SafeVarargs
  protected final <T> void mountReport(String path, @NotNull RouteDescription
    description, RouteHandler<T> handler, @Nullable final Handler<CorrelationContext>...
                                         middlewares) {
    mount(path, true, HttpMethod.REPORT, description, handler, middlewares);
  }


  @SafeVarargs
  protected final <T> void mountCheckIn(String path, @NotNull RouteDescription
    description, RouteHandler<T> handler, @Nullable final Handler<CorrelationContext>...
                                          middlewares) {
    mount(path, false, HttpMethod.CHECKIN, description, handler, middlewares);
  }


  @SafeVarargs
  protected final <T> void mountCheckOut(String path, @NotNull RouteDescription
    description, RouteHandler<T> handler, @Nullable final Handler<CorrelationContext>...
                                           middlewares) {
    mount(path, false, HttpMethod.CHECKOUT, description, handler, middlewares);
  }


  @SafeVarargs
  protected final <T> void mountUncheckOut(String path, @NotNull RouteDescription
    description, RouteHandler<T> handler, @Nullable final Handler<CorrelationContext>...
                                             middlewares) {
    mount(path, false, HttpMethod.UNCHECKOUT, description, handler, middlewares);
  }


  @SafeVarargs
  protected final <T> void mountMerge(String path, @NotNull RouteDescription
    description, RouteHandler<T> handler, @Nullable final Handler<CorrelationContext>...
                                        middlewares) {
    mount(path, true, HttpMethod.MERGE, description, handler, middlewares);
  }


  @SafeVarargs
  protected final <T> void mountAcl(String path, @NotNull RouteDescription
    description, RouteHandler<T> handler, @Nullable final Handler<CorrelationContext>...
                                      middlewares) {
    mount(path, true, HttpMethod.ACL, description, handler, middlewares);
  }


  @SafeVarargs
  protected final <T> void mountCustom(String path, HttpMethod
                                         method, @NotNull RouteDescription description, RouteHandler<T> handler,
                                       @Nullable final Handler<CorrelationContext>... middlewares) {
    mount(path, false, method, description, handler, middlewares);
  }

  @SafeVarargs
  protected final void mountStatic(String path, String fileSystemPath,
                                   @Nullable final Handler<CorrelationContext>... middlewares) {
    final String fullPath;

    //? This is for unversioned APIs
    if (version == 0) {
      fullPath = noTrailingSlash("/%s/%s/*".formatted(this.basePath, path));
    }

    //? This is for versioned APIs
    else {
      fullPath = noTrailingSlash("/v%d/%s/%s/*".formatted(this.version, this.basePath, path));
    }

    final var route = registrant.router.route().path(fullPath);

    if (middlewares != null) {
      for (final var middleware : middlewares) {
        if (middleware == null) continue;
        route.handler(wrapMiddleware(middleware));
      }
    }

    //? This is fine
    route.blockingHandler(StaticHandler.create(fileSystemPath));

    //? Log this kind of.
    if (ConfigurationRegistrant.deployedServers.size() == AppEnvironment.getInstance().getServerCount() - 1) {

      registrant.vertx.executeBlocking(() -> {
        logger.atInfo()
          .addKeyValue("handler", getClass().getSimpleName())
          .addKeyValue("path", fullPath)
          .log("Mounted a '\u001B[32mstatic file\u001B[0m' handler which listens on '{}'", fullPath);
        return null;
      }).await();
    }

    //? Increment counter.
    totalCount++;
  }

  public abstract void registerRoutes();

  public interface RouteHandler<T> {
    void handle(CorrelationContext context, Promise<TypedServiceResult<T>> promise) throws ErrorResult;
  }
}
