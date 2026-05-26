package io.github.the_infinite.framework.utils;

import java.util.List;
import java.util.Optional;

import io.github.the_infinite.framework.logging.correlation.CorrelationContext;
import io.vertx.ext.web.validation.RequestParameters;
import io.vertx.ext.web.validation.ValidationHandler;

@SuppressWarnings("unused")
public final class RequestParser {
  private RequestParser() {
    // Prevent instantiation
  }

  /**
   * Extracts the JSON body and maps it directly to your DTO/POJO.
   * This relies on the JsonBodyProcessorImpl already mounted in your RouteController.
   */
  public static <T> T getBodyAs(CorrelationContext context, Class<T> clazz) {
    try {
      return context.router().body().asPojo(clazz);
    } catch (IllegalArgumentException e) {
      // Thrown if the body cannot be mapped to the class (e.g., malformed JSON matching)
      throw new RuntimeException("Malformed request body. Expected format of: " + clazz.getSimpleName(), e);
    }
  }

  /**
   * Extracts a path parameter (e.g., /users/:id -> getPathParam(context, "id"))
   */
  public static String getPathParam(CorrelationContext context, String paramName) {
    return context.router().pathParam(paramName);
  }

  /**
   * Extracts all values for a given query parameter (e.g., status=active&status=pending)
   */
  public static List<String> getQueryParamAll(CorrelationContext context, String paramName) {
    return context.router().queryParam(paramName);
  }

  /**
   * Extracts the first value of a query parameter safely.
   */
  public static Optional<String> getQueryParam(CorrelationContext context, String paramName) {
    final var params = getQueryParamAll(context, paramName);
    return params.isEmpty() ? Optional.empty() : Optional.of(params.getFirst());
  }

  /**
   * If you start using Vert.x's OpenAPI or advanced ValidationHandler schemas in the future,
   * this will extract the safely validated parameter object.
   */
  public static RequestParameters getValidatedParams(CorrelationContext context) {
    return context.router().get(ValidationHandler.REQUEST_CONTEXT_KEY);
  }
}
