package io.github.the_infinite.framework.logging.correlation;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.github.the_infinite.framework.data.DBSessionListener;
import io.github.the_infinite.framework.middleware.GeneralMiddlewares;
import io.github.the_infinite.framework.response.ErrorResult;
import io.github.the_infinite.framework.utils.DataHelpers;
import io.github.the_infinite.framework.validation.ValidationException;
import io.github.the_infinite.framework.validation.Validator;
import io.vertx.core.Context;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.json.Json;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.validation.RequestParameters;
import io.vertx.ext.web.validation.ValidationHandler;
import lombok.extern.slf4j.Slf4j;

@SuppressWarnings("unused")
@Slf4j
public final class CorrelationContext implements AutoCloseable {
  private static final String CONTEXT_KEY = "CORRELATION_ID";
  private static final String CONTEXT_USER = "TVT_USER_ID";
  private static final String INSTANCE_KEY = "TVT_CORRELATION_CONTEXT";
  private static final String REQUEST_ID = "TVT_REQUEST_ID";
  private final RoutingContext routingContext;
  private final AtomicReference<Session> session;
  private final Context vertxContext;
  private final AtomicInteger holdLock;

  //? This is fine too.
  private CorrelationContext(@NotNull RoutingContext routingContext) {
    String id = routingContext.get(CONTEXT_KEY);

    //? This is our context in question.
    if (id == null) {
      routingContext.put(CONTEXT_KEY, DataHelpers.createToken());
    }

    //? This is fine too.
    this.routingContext = routingContext;
    this.vertxContext = null;
    this.holdLock = new AtomicInteger(0);
    this.session = new AtomicReference<>(null);
  }

  private CorrelationContext(@NotNull Context vertxContext) {
    String id = vertxContext.get(CONTEXT_KEY);

    //? This is our context in question.
    if (id == null) {
      vertxContext.put(CONTEXT_KEY, DataHelpers.createToken());
    }

    //? This is fine too.
    this.routingContext = null;
    this.vertxContext = vertxContext;
    this.holdLock = new AtomicInteger(0);
    this.session = new AtomicReference<>(null);
  }

  public static CorrelationContext from(@NotNull RoutingContext routingContext) {
    final CorrelationContext existing = routingContext.get(INSTANCE_KEY);
    if (existing != null) {
      return existing;
    }
    final var created = new CorrelationContext(routingContext);
    routingContext.put(INSTANCE_KEY, created);
    return created;
  }

  public static CorrelationContext from(@NotNull Context vertxContext) {
    final CorrelationContext existing = vertxContext.get(INSTANCE_KEY);
    if (existing != null) {
      return existing;
    }
    final var created = new CorrelationContext(vertxContext);
    vertxContext.put(INSTANCE_KEY, created);
    return created;
  }

  /// Gets the pure form of the routing context. Useful in situations where there is a need to log this out in a certain
  /// sense away from a given action. This cannot be set if this correlation context was not created with a `RoutingContext`.
  public RoutingContext router() {
    if (routingContext == null) {
      throw new UnsupportedOperationException("Cannot get routing context because this is not a correlation group of a HTTP request");
    }
    return routingContext;
  }

  /// Gets the pure form of the Vertx context. Useful in situations where there is a need to log this out in a certain
  /// sense away from a given action. This cannot be set if this correlation context was not created with a `Context`.
  @NotNull
  public Context vertx() {
    if (vertxContext == null) {
      throw new UnsupportedOperationException("Cannot get vertx context because this is not a correlation group of a Vertx context");
    }
    return vertxContext;
  }

  /// Gets the correlation ID of this correlation context. Useful for monitoring instances where we want to trace events
  ///  associated with a given property from one end of the system to another.
  public String correlationId() {
    return get(CONTEXT_KEY);
  }

  /// Gets the ID of the user associated with this correlation context. For monitoring and
  /// observability typically.
  public Optional<String> userId() {
    return Optional.ofNullable(get(CONTEXT_USER));
  }

  /// Gets the request IP address for this correlation context.
  /// For HTTP requests, this prefers forwarded headers and falls back to the request's remote address.
  /// This cannot be set if this correlation context was not created with a `RoutingContext`.
  public String requestIpAddress() {
    if (routingContext == null) {
      throw new UnsupportedOperationException("Cannot get request IP address because this is not a correlation group of a HTTP request");
    }

    final var request = routingContext.request();
    final var forwardedFor = request.getHeader("X-Forwarded-For");
    if (forwardedFor != null && !forwardedFor.isBlank()) {
      final var forwardedIp = forwardedFor.split(",", 2)[0].trim();
      if (!forwardedIp.isEmpty()) {
        return forwardedIp;
      }
    }

    final var realIp = request.getHeader("X-Real-IP");
    if (realIp != null && !realIp.isBlank()) {
      return realIp.trim();
    }

    final var remoteAddress = request.remoteAddress();
    if (remoteAddress != null) {
      return remoteAddress.hostAddress();
    }

    throw new IllegalStateException("Cannot determine request IP address because the remote address is unavailable");
  }

  public void setRequestId(String requestId) {
    set(REQUEST_ID, requestId);
  }

  public String getRequestId() {
    return get(REQUEST_ID);
  }

  /// Is the context this correlation context enclosing still useful? Used as a means of checking to avoid throwing
  /// errors that could have been otherwise avoided.
  public boolean isAvailable() {
    if (vertxContext != null) {
      return true;
    }

    //? Get the response here.
    final var response = routingContext.response();

    //? We want to make sure it has neither closed nor ended.
    return !(response.ended() || response.closed());
  }

  /// Gets the request parameters of this correlation context. This cannot be set if this
  /// correlation context was not created with a `RoutingContext`.
  public RequestParameters params() {
    if (routingContext == null) {
      throw new UnsupportedOperationException("Cannot get request parameters because this is not a correlation group of a HTTP request");
    }
    return routingContext.get(ValidationHandler.REQUEST_CONTEXT_KEY);
  }

  //? Getting the correlation context here as needed.
  @Override
  public void close() throws Exception {
    if (session == null || session.get() == null || !session.get().isOpen()) {
      return;
    }

    holdLock.decrementAndGet();
    if (holdLock.get() > 0) {
      return;
    }

    final var foundSession = session.get();
    try {
      foundSession.flush();
    } catch (Exception ignored) {
    }
    foundSession.close();
    session.set(null);
  }

  public void holdLock() {
    holdLock.incrementAndGet();
  }

  /**
   * Gets the session from the correlation context. If the session is not set, it will be
   * created.
   */
  public @NotNull Session getSession(SessionFactory factory) {
    if (session.get() != null) {
      return session.get();
    }

    holdLock.incrementAndGet();
    final var foundSession = factory.openSession();
    foundSession.addEventListeners(new DBSessionListener());
    session.set(foundSession);
    return foundSession;
  }


  /// Gets the request body of this correlation context, deserializes it into the given type, and
  /// validates it using the framework's fail-fast annotation validator. The first validation
  /// failure raises an `ErrorResult` carrying the offending field. This cannot be set if this
  /// correlation context was not created with a `RoutingContext`.
  public <TBody> TBody body(Class<TBody> type) throws ErrorResult {
    if (routingContext == null) {
      throw new UnsupportedOperationException("Cannot get request body because this is not a correlation group of a HTTP request");
    }

    try {
      final TBody result;

      // Multipart test requests encode the actual JSON payload into a single `body` form field.
      if (isMultipartRequest()) {
        final var formBody = getFormBodyPayload();
        if (formBody != null && !formBody.isBlank()) {
          result = type == String.class ? type.cast(formBody) : Json.decodeValue(formBody, type);
        } else if (type == String.class) {
          result = type.cast(routingContext.body().asString());
        } else {
          result = routingContext.body().asPojo(type);
        }
      } else if (type == String.class) {
        result = type.cast(routingContext.body().asString());
      } else {
        result = routingContext.body().asPojo(type);
      }

      //? Validate the deserialized DTO with the framework's fail-fast annotation validator.
      //? Raw String payloads are skipped because they are not structured DTOs.
      if (type != String.class && result != null) {
        Validator.validate(result);
      }

      return result;
    } catch (IllegalArgumentException e) {
      Throwable cause = e;

      if (e.getCause() != null) {
        cause = e.getCause();
      }

      throw ErrorResult.of(cause);
    } catch (ValidationException e) {
      throw ErrorResult.of(e);
    }
  }

  private boolean isMultipartRequest() {
    final var contentType = routingContext.request().getHeader(HttpHeaders.CONTENT_TYPE);
    return contentType != null && contentType.toLowerCase().startsWith("multipart/form-data");
  }

  private String getFormBodyPayload() {
    return routingContext.request().formAttributes().get("body");
  }

  public CorrelationContext withCorrelationId(String id) {
    set(CONTEXT_KEY, id);
    return this;
  }

  public <T> T get(String name) {
    if (routingContext != null) {
      return routingContext.get(name);
    }

    if (vertxContext != null) {
      return vertxContext.get(name);
    }

    throw new UnsupportedOperationException("Cannot get value because this correlation context does not have a valid context");
  }

  public void setUserId(String value) {
    set(CONTEXT_USER, value);
  }

  public void set(String name, Object value) {
    if (routingContext != null) {
      routingContext.data().put(name, value);
      return;
    }

    if (vertxContext != null) {
      vertxContext.put(name, value);
      return;
    }

    throw new UnsupportedOperationException("Cannot set value because this correlation context does not have a valid context");
  }

  public String getPaginationCursor() {
    return get(GeneralMiddlewares.PAGINATION_CURSOR);
  }

  public Integer getPaginationLimit() {
    return get(GeneralMiddlewares.PAGINATION_LIMIT);
  }

  /// Helper function used to return a new empty promise. This does not bother with any processing; therefore, the result
  /// is guaranteed to be an unresolved promise.
  public <T> Promise<T> promise() {
    return Promise.promise();
  }
}
