package io.github.the_infinite.framework.doc;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import io.vertx.core.json.JsonObject;

/**
 * Metadata record that describes a route for documentation purposes.
 * It includes route details such as name, description, DTO mappings, and configuration overrides.
 *
 */
@SuppressWarnings("unused")
public record RouteDescription(String name, String description,
                               @Nullable Class<?> requestBodyClass,
                               Map<Integer, DocumentableDTO> responseDTOs,
                               @Nullable Map<String, String> headers,
                               @Nullable Map<String, String> pathParameters,
                               @Nullable Map<String, String> pathParameterDefaults,
                               @Nullable Map<String, String> queryParameters,
                               @Nullable Map<String, String> queryParameterDefaults,
                               @Nullable Integer rateLimit,
                               boolean authenticationRequired,
                               @Nullable String authenticationComment) {

  private static final DocumentationRegistrant registrant =
    DocumentationRegistrant.getInstance();

  /**
   * @param name                   Human-readable name of the route.
   * @param description            A detailed description of what the route does.
   * @param requestBodyClass       The class of the DTO used in the request body, or null if not applicable.
   * @param responseDTOs           A map where keys are HTTP status codes and values are the corresponding DTO classes.
   * @param headers                A map of headers specifically for this route, which can override global headers.
   * @param pathParameters         A map of path parameters and their descriptions.
   * @param queryParameters        A map of query parameters and their descriptions.
   * @param queryParameterDefaults A map of query parameters and their default values.
   * @param rateLimit              An optional rate limit override for this route.
   * @param authenticationRequired Whether authentication is required to access this route.
   * @param authenticationComment   The reason why authentication is or isn't required.
   */
  public RouteDescription {
  }

  /**
   * Backward compatible constructor.
   *
   * @param name                   Human-readable name of the route.
   * @param description            A detailed description of what the route does.
   * @param requestBodyClass       The class of the DTO used in the request body, or null if not applicable.
   * @param responseDTOs           A map where keys are HTTP status codes and values are the corresponding DTO classes.
   * @param headers                A map of headers specifically for this route, which can override global headers.
   * @param pathParameters         A map of path parameters and their descriptions.
   * @param queryParameters        A map of query parameters and their descriptions.
   * @param rateLimit              An optional rate limit override for this route.
   * @param authenticationRequired Whether authentication is required to access this route.
   * @param authenticationComment   The reason why authentication is or isn't required.
   */
  public RouteDescription(String name, String description,
                          @Nullable Class<?> requestBodyClass,
                          Map<Integer, DocumentableDTO> responseDTOs,
                          @Nullable Map<String, String> headers,
                          @Nullable Map<String, String> pathParameters,
                          @Nullable Map<String, String> queryParameters,
                          @Nullable Integer rateLimit,
                          boolean authenticationRequired,
                          @Nullable String authenticationComment) {
    this(name, description, requestBodyClass, responseDTOs, headers, pathParameters, null, queryParameters, null, rateLimit, authenticationRequired, authenticationComment);
  }

  /**
   * Creates a new builder for RouteDescription.
   * @return A new Builder instance.
   */
  public static Builder builder() {
    return new Builder();
  }

  @Override
  public Integer rateLimit() {
    if (this.rateLimit != null) {
      return this.rateLimit;
    }

    return registrant.getGlobalRateLimit();
  }

  @Override
  public Map<String, String> headers() {
    final var completeHeaders = new HashMap<>(registrant.getGlobalHeaders());

    if (this.headers != null && !this.headers.isEmpty()) {
      completeHeaders.putAll(this.headers);
    }

    return Collections.unmodifiableMap(completeHeaders);
  }

  public static class Builder {
    private final Map<String, String> pathParameters = new HashMap<>();
    private final Map<String, String> pathParameterDefaults = new HashMap<>();
    private final Map<String, String> queryParameters = new HashMap<>();
    private final Map<String, String> queryParameterDefaults = new HashMap<>();
    private String name;
    private String description;
    private Class<?> requestBodyClass;
    private final Map<Integer, DocumentableDTO> responseDTOs = new HashMap<>();
    private Map<String, String> headers = new HashMap<>();
    private Integer rateLimit;
    private boolean authenticationRequired = false;
    private String authenticationComment;

    public Builder name(String name) {
      this.name = name;
      return this;
    }

    public Builder description(String description) {
      this.description = description;
      return this;
    }

    public Builder requestBodyClass(Class<?> requestBodyClass) {
      this.requestBodyClass = requestBodyClass;
      return this;
    }

    public Builder addResponseDTO(int statusCode, Class<? extends DocumentableDTO> dtoClass) {
      DocumentableDTO innerInstance = resolveDocumentableDTO(dtoClass);
      String status = statusCode >= 400 ? "error" : "success";
      String message = statusCode >= 500 ? "Internal server error"
        : statusCode >= 400 ? "Bad request"
        : "Operation completed successfully";
      this.responseDTOs.put(statusCode, new ResponseCasing(status, message, innerInstance));
      return this;
    }

    private DocumentableDTO resolveDocumentableDTO(Class<? extends DocumentableDTO> dtoClass) {
      try {
        return dtoClass.getDeclaredConstructor().newInstance();
      } catch (Exception ignored) {
        // Fallback to explicit @ResponseExample static fields for DTOs without a no-arg constructor.
      }

      try {
        for (Field field : dtoClass.getDeclaredFields()) {
          if (!field.isAnnotationPresent(ResponseExample.class)) {
            continue;
          }

          if (!Modifier.isStatic(field.getModifiers()) || !Modifier.isFinal(field.getModifiers())) {
            continue;
          }

          field.setAccessible(true);
          Object value = field.get(null);
          if (value instanceof DocumentableDTO example) {
            return example;
          }
        }
      } catch (Exception ignored) {
        // Fallback below keeps docs generation resilient.
      }

      return new MissingExampleDTO(dtoClass.getSimpleName());
    }

    private record MissingExampleDTO(String dtoName) implements DocumentableDTO {
      @Override
      public String toExample() {
        return new JsonObject()
          .put("class", dtoName)
          .put("info", "No example available")
          .encodePrettily();
      }
    }

    public Builder headers(Map<String, String> headers) {
      this.headers = headers;
      return this;
    }

    public Builder addHeader(String key, String value) {
      this.headers.put(key, value);
      return this;
    }

    public Builder addPathParameter(String name, String description) {
      this.pathParameters.put(name, description);
      return this;
    }

    public Builder addPathParameter(String name, String description, @NotNull String defaultValue) {
      this.pathParameters.put(name, description);
      this.pathParameterDefaults.put(name, defaultValue);
      return this;
    }

    public Builder addQueryParameter(String name, String description) {
      this.queryParameters.put(name, description);
      return this;
    }

    public Builder addQueryParameter(String name, String description,
                                     @NotNull String defaultValue) {
      this.queryParameters.put(name, description);
      this.queryParameterDefaults.put(name, defaultValue);
      return this;
    }

    public Builder rateLimit(Integer rateLimit) {
      this.rateLimit = rateLimit;
      return this;
    }

    public Builder authenticationRequired(boolean authenticationRequired) {
      this.authenticationRequired = authenticationRequired;
      return this;
    }

    public Builder authenticationComment(String authenticationComment) {
      this.authenticationComment = authenticationComment;
      return this;
    }

    public RouteDescription build() {
      return new RouteDescription(name, description, requestBodyClass, responseDTOs, headers, pathParameters, pathParameterDefaults, queryParameters, queryParameterDefaults, rateLimit, authenticationRequired, authenticationComment);
    }
  }


  /**
   * Represents the standard response envelope used by all API endpoints.
   * Every response is wrapped in this shell with a status, message, and data payload.
   */
  public record ResponseCasing(String status, String message,
                                      @Nullable DocumentableDTO data) implements DocumentableDTO {
    @Override
    public String toExample() {
      Object parsedData = null;
      if (data != null) {
        String raw = data.toExample();
        try {
          parsedData = new JsonObject(raw);
        } catch (Exception ignored) {
          try {
            parsedData = new io.vertx.core.json.JsonArray(raw);
          } catch (Exception ignoredToo) {
            parsedData = raw;
          }
        }
      }

      return new JsonObject()
        .put("status", status)
        .put("message", message)
        .put("data", parsedData)
        .encodePrettily();
    }

    /**
     * Returns the simple name of the inner data DTO, for display purposes.
     */
    public String innerTypeName() {
      return data != null ? data.getClass().getSimpleName() : "Response";
    }
  }
}
