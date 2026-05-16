package io.github.the_infinite.framework.logging.correlation;

import io.github.the_infinite.framework.utils.DataHelpers;

import org.jetbrains.annotations.NotNull;

import io.vertx.core.Context;
import io.vertx.core.Promise;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.validation.RequestParameters;
import io.vertx.ext.web.validation.ValidationHandler;

@SuppressWarnings("unused")
public final class CorrelationContext {
    private static final String CONTEXT_KEY = "CORRELATION_ID";
    private final String id;
    private final RoutingContext routingContext;
    private final Context vertxContext;

    //? This is fine too.
    private CorrelationContext(@NotNull RoutingContext routingContext) {
        String id = routingContext.get(CONTEXT_KEY);

        //? This is our context in question.
        if (id == null) {
            routingContext.put(CONTEXT_KEY, DataHelpers.createToken());
        }

        //? This is fine too.
        this.id = routingContext.get(CONTEXT_KEY);
        this.routingContext = routingContext;
        this.vertxContext = null;
    }

    private CorrelationContext(@NotNull Context vertxContext) {
        String id = vertxContext.get(CONTEXT_KEY);

        //? This is our context in question.
        if (id == null) {
            vertxContext.put(CONTEXT_KEY, DataHelpers.createToken());
        }

        //? This is fine too.
        this.id = vertxContext.get(CONTEXT_KEY);
        this.routingContext = null;
        this.vertxContext = vertxContext;
    }

    /// Build a correlation context using a routing context.
    public static CorrelationContext from(@NotNull RoutingContext routingContext) {
        return new CorrelationContext(routingContext);
    }

    ///  Build a context from a given vertx context.
    public static CorrelationContext from(@NotNull Context context) {
        return new CorrelationContext(context);
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
        return id;
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


    /// Gets the request body of this correlation context. This cannot be set if this
    /// correlation context was not created with a `RoutingContext`.
    public <TBody extends Record> TBody body(Class<TBody> type) {
        if (routingContext == null) {
            throw new UnsupportedOperationException("Cannot get request body because this is not a correlation group of a HTTP request");
        }
        var params = params();
        return params.body().getJsonObject().mapTo(type);
    }

    public CorrelationContext withCorrelationId(String id) {
        if (routingContext != null) {
            routingContext.data().put(CONTEXT_KEY, id);
        }

        if (vertxContext != null) {
            vertxContext.put(CONTEXT_KEY, id);
        }

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

    /// Helper function used to return a new empty promise. This does not bother with any processing therefore the result
    /// is guaranteed to be an unresolved promise.
    public <T> Promise<T> promise() {
        return Promise.promise();
    }
}
