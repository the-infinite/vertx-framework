package io.github.the_infinite.framework.response;

import java.util.Objects;

import io.github.the_infinite.framework.env.AppEnvironment;
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
      if (Objects.requireNonNull(t) instanceof ErrorResult er) {
        return er;
      } else if (t instanceof PgException e) {
        return new ErrorResult(e.getErrorMessage(), e.getMessage(), 500);
      } else if (t instanceof ParameterProcessorException e) {
        return new ErrorResult(e.getMessage(),
          "%s at %s".formatted(e.getParameterName(),
            e.getLocation().name()),
          400);
      } else if (t instanceof BodyProcessorException e) {
        return new ErrorResult(e.getMessage(),
          "%s found %s".formatted(e.getErrorType().name(),
            e.getActualContentType())
          , 400);
      } else if (t instanceof RequestPredicateException e) {
       return new ErrorResult(e.getMessage(),
          Objects.requireNonNullElse(e.getCause(), t).getMessage(), 400);
      } else {
        return new ErrorResult(t.getMessage(),
          env.getKind() == AppEnvironment.EnvironmentKind.PRODUCTION ?
            "An unexpected error occurred." : t,
          500);
      }
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
