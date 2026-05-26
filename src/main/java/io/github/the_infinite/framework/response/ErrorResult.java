package io.github.the_infinite.framework.response;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.ValueInstantiationException;

import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

import io.github.the_infinite.framework.env.AppEnvironment;
import io.github.the_infinite.framework.logging.console.ConsoleLogger;
import io.github.the_infinite.framework.utils.DataHelpers;
import io.vertx.ext.web.validation.BodyProcessorException;
import io.vertx.ext.web.validation.ParameterProcessorException;
import io.vertx.ext.web.validation.RequestPredicateException;
import io.vertx.pgclient.PgException;
import lombok.Getter;

@SuppressWarnings("unused")
public class ErrorResult extends Exception {
  private static final Pattern ABBREVIATION_PATTERN = Pattern.compile("(\\w)[\\w]*([./])");

  protected final String message;

  @Getter
  protected final Object data;

  @Getter
  protected final int code;

  public ErrorResult(String message, Object data, int code) {
    super(message);
    final var env = AppEnvironment.getInstance();
    this.message = ABBREVIATION_PATTERN.matcher(message).replaceAll("$1.");
    this.code = code;
    this.data = buildData(data, code);
  }

  public static ErrorResult of(Throwable t) {
    final var env = AppEnvironment.getInstance();
    return switch (t) {
      case ErrorResult er -> er;
      case PgException e -> new ErrorResult(e.getErrorMessage(), e.getMessage(), 500);
      case ValueInstantiationException e ->
        new ErrorResult(e.getMessage().split("\\n")[0], e, 400);
      case MismatchedInputException e ->
        new ErrorResult(e.getMessage().split("\\n")[0], e, 400);
      case ParameterProcessorException e -> new ErrorResult(e.getMessage(),
        "%s at %s".formatted(e.getParameterName(),
          e.getLocation().name()),
        400);
      case IllegalArgumentException e -> new ErrorResult(e.getMessage(), e, 400);
      case IllegalStateException e -> new ErrorResult(e.getMessage(), e, 400);
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

  private static Object buildData(Object data, int code) {
    if (!(data instanceof Throwable)) {
      return data;
    }

    final var env = AppEnvironment.getInstance();
    if (env.getKind() == AppEnvironment.EnvironmentKind.PRODUCTION) {
      return null;
    }

    final var error = ExceptionDto.from((Throwable) data);

    if (code < 500) {
      ConsoleLogger.getInstance().error(error.toString());
      return null;
    }

    return error;
  }

  public TypedServiceResult<Object> toServiceResult() {
    return new ServiceResult<>("error", message, data, code);
  }

  public record ExceptionDto(
    String type,
    String message,
    List<String> stackTrace
  ) {
    /**
     * Safely transforms a raw Throwable into a serialization-friendly DTO.
     * * @param throwable The raw exception caught by the framework.
     * @return A clean, JSON-ready representation of the exception.
     */
    public static ExceptionDto from(Throwable throwable) {
      if (throwable == null) {
        return null;
      }

      return new ExceptionDto(
        throwable.getClass().getName(),
        Objects.requireNonNullElse(
          throwable.getMessage(),
          "Unknown error"
        ),
        Arrays.stream(throwable.getStackTrace())
          .map(StackTraceElement::toString)
          .toList()
      );
    }

    @Override
    public @NotNull String toString() {
      try {
        return DataHelpers.serializeObject(this);
      } catch (JsonProcessingException e) {
        return "ExceptionDto{type=%s, message=%s}".formatted(type, message);
      }
    }
  }
}
