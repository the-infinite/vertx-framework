package io.github.the_infinite.core.response;

import io.github.the_infinite.core.env.AppEnvironment;

import java.util.Objects;

import io.vertx.ext.web.validation.BodyProcessorException;
import io.vertx.ext.web.validation.ParameterProcessorException;
import io.vertx.ext.web.validation.RequestPredicateException;
import io.vertx.pgclient.PgException;

@SuppressWarnings("unused")
public class ErrorResult extends Exception {
    protected final String message;

    protected final Object data;
    protected final int code;

    public ErrorResult(String message, Object data, int code) {
        super(message);
        this.message = message;
        this.data = data;
        this.code = code;
    }

    public static ErrorResult of(Throwable t) {
        final var env = AppEnvironment.getInstance();
        return switch (t) {
            case ErrorResult er -> er;
            case PgException e ->
                    new ErrorResult(e.getErrorMessage(), e.getMessage(), 500);
            case ParameterProcessorException e -> new ErrorResult(e.getMessage(),
                    "%s at %s".formatted(e.getParameterName(),
                            e.getLocation().name()),
                    400);
            case BodyProcessorException e -> new ErrorResult(e.getMessage(),
                    "%s found %s".formatted(e.getErrorType().name(),
                            e.getActualContentType())
                    , 400);
            case RequestPredicateException e -> new ErrorResult(e.getMessage(),
                    Objects.requireNonNullElse(e.getCause(), t).getMessage(), 400);
            default -> new ErrorResult(t.getMessage(),
                    env.getKind() == AppEnvironment.EnvironmentKind.PRODUCTION ?
                            "An unexpected error occurred." : t,
                    500);
        };
    }

    public TypedServiceResult<Object> toServiceResult() {
        return new ServiceResult<>("error", message, data, code);
    }

    public Object getData() {
        return data;
    }

    public int getCode() {
        return code;
    }
}
