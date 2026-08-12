package io.github.the_infinite.framework.doc;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jetbrains.annotations.NotNull;

import java.lang.reflect.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.*;
import java.util.*;

import io.github.the_infinite.framework.env.AppEnvironment;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Produces a valid <a href="https://spec.openapis.org/oas/v3.1.0.html">OpenAPI 3.1</a>
 * specification document from the routes and settings registered in the
 * {@link DocumentationRegistrant}.
 *
 * <p>The generated document is consumed by the Swagger UI static distribution that is
 * served by the {@link DocumentationController}, so it deliberately favours
 * <code>example</code> rich payloads over hand-built schema models.</p>
 */
public final class OpenApi3Generator {
  private static final String OPENAPI_VERSION = "3.1.0";
  private static final String DOCUMENTATION_CONTROLLER = "DocumentationController";
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Set<String> RESERVED_HEADER_PARAMETERS = Set.of(
    "accept", "content-type", "authorization"
  );

  private OpenApi3Generator() {
  }

  /**
   * Builds the complete OpenAPI 3.1 document for the given registrant.
   *
   * @param registrant the documentation registry holding all metadata.
   * @return the OpenAPI specification as a {@link JsonObject}.
   */
  public static JsonObject generate(DocumentationRegistrant registrant) {
    final var spec = new JsonObject()
      .put("openapi", OPENAPI_VERSION)
      .put("info", buildInfo(registrant))
      .put("servers", buildServers())
      .put("paths", buildPaths(registrant));

    final var components = resolveComponents(registrant);
    if (securitySchemeKey(components) != null) {
      spec.put("components", components);
      spec.put("security", new JsonArray());
    }

    final var groups = buildGroupDescriptionsExtension(registrant);
    if (!groups.isEmpty()) {
      spec.put("x-groups", groups);
    }

    return spec;
  }

  private static JsonObject buildInfo(DocumentationRegistrant registrant) {
    final var info = new JsonObject();

    try {
      final var env = AppEnvironment.getInstance();
      info.put("title", env.getName());
      info.put("version", env.getVersionCode());
    } catch (Exception ignored) {
      info.put("title", "API Documentation");
      info.put("version", "1.0.0");
    }

    info.put("description", buildGlobalDescription(registrant));
    return info;
  }

  private static JsonArray buildServers() {
    return new JsonArray().add(new JsonObject().put("url", "/"));
  }

  private static String buildGlobalDescription(DocumentationRegistrant registrant) {
    final var lines = new ArrayList<String>();

    lines.add("Live API specification for this service.");
    lines.add("");
    lines.add(String.format("Default rate limit: %s", registrant.getGlobalRateLimit() != null
      ? registrant.getGlobalRateLimit() + " requests per minute"
      : "Unlimited"));

    if (!registrant.getAuthSettings().isEmpty()) {
      lines.add("");
      lines.add("Authentication settings:");
      registrant.getAuthSettings().forEach((key, value) ->
        lines.add(String.format("- `%s`: `%s`", key, value)));
    }

    return String.join("\n", lines);
  }

  private static JsonObject buildPaths(DocumentationRegistrant registrant) {
    final var paths = new JsonObject();
    final var registersWithAuthentication = routeRegistersAuthentication(registrant);

    groupRoutes(registrant).forEach((_, routes) -> {
      for (DocumentationRegistrant.RegisteredRoute route : routes) {
        final var description = route.description();
        if (description == null || description.group() == null) {
          continue;
        }

        final var openApiPath = openApiPath(route.path());
        if (!paths.containsKey(openApiPath)) {
          paths.put(openApiPath, new JsonObject());
        }

        paths.getJsonObject(openApiPath)
          .put(route.method().toLowerCase(Locale.ROOT),
            buildOperation(description, route.path(), registersWithAuthentication));
      }
    });

    return paths;
  }

  private static JsonObject buildOperation(RouteDescription description, String routePath, boolean registersWithAuthentication) {
    final var operation = new JsonObject()
      .put("summary", nonNull(description.name()))
      .put("operationId", operationId(description.name(), routePath));
    if (description.description() != null && !description.description().isBlank()) {
      operation.put("description", description.description());
    }
    operation.put("tags", new JsonArray().add(description.group()));

    final var parameters = buildParameters(description);
    if (!parameters.isEmpty()) {
      operation.put("parameters", parameters);
    }

    final var requestBody = buildRequestBody(description);
    if (requestBody != null) {
      operation.put("requestBody", requestBody);
    }

    operation.put("responses", buildResponses(description));

    operation.put("x-authentication", new JsonObject()
      .put("required", description.authenticationRequired())
      .put("comment", nonNull(description.authenticationComment())));

    final var rateLimit = effectiveRateLimit(description);
    if (rateLimit != null && rateLimit > 0) {
      operation.put("x-rate-limit", new JsonObject()
        .put("limit", rateLimit)
        .put("period", "minute"));
    }

    if (description.authenticationRequired() && registersWithAuthentication) {
      operation.put("security", new JsonArray().add(new JsonObject().put("authentication", new JsonArray())));
    }

    return operation;
  }

  private static JsonArray buildParameters(RouteDescription description) {
    final var parameters = new JsonArray();

    if (description.pathParameters() != null) {
      description.pathParameters().forEach((name, paramDescription) -> {
        final var schema = new JsonObject().put("type", "string");
        putDefaultIfPresent(schema, description.pathParameterDefaults(), name);
        parameters.add(new JsonObject()
          .put("name", name)
          .put("in", "path")
          .put("required", true)
          .put("description", nonNull(paramDescription))
          .put("schema", schema));
      });
    }

    if (description.queryParameters() != null) {
      description.queryParameters().forEach((name, paramDescription) -> {
        final var schema = new JsonObject().put("type", "string");
        putDefaultIfPresent(schema, description.queryParameterDefaults(), name);
        parameters.add(new JsonObject()
          .put("name", name)
          .put("in", "query")
          .put("required", false)
          .put("description", nonNull(paramDescription))
          .put("schema", schema));
      });
    }

    description.headers().forEach((name, value) -> {
      if (RESERVED_HEADER_PARAMETERS.contains(name.toLowerCase(Locale.ROOT))) {
        return;
      }
      parameters.add(new JsonObject()
        .put("name", name)
        .put("in", "header")
        .put("required", false)
        .put("description", nonNull(value))
        .put("schema", new JsonObject()
          .put("type", "string")
          .put("default", "")));
    });

    return parameters;
  }

  private static void putDefaultIfPresent(JsonObject schema, Map<String, String> defaults, String name) {
    if (defaults != null && defaults.containsKey(name)) {
      schema.put("default", defaults.get(name));
    }
  }

  private static JsonObject buildRequestBody(RouteDescription description) {
    final var fileParameters = description.fileParameters();
    if (fileParameters != null && !fileParameters.isEmpty()) {
      final var properties = new JsonObject();
      properties.put("body", new JsonObject()
        .put("type", "string")
        .put(
          "description",
          "JSON-encoded application payload sent alongside the uploaded files, if any. " +
            "Switch to the application/json to see the type information"));
      fileParameters.forEach((name, parameter) -> {
        final var schema = new JsonObject()
          .put("type", "string")
          .put("format", "binary")
          .put("description", nonNull(parameter.description()));
        if (parameter.limit() != null) {
          schema.put("x-max-files", parameter.limit());
        }
        properties.put(name, schema);
      });

      final var content = new JsonObject();
      content.put("multipart/form-data", new JsonObject()
        .put("schema", new JsonObject()
          .put("type", "object")
          .put("properties", properties)));

      final var dtoClass = description.requestBodyClass();
      if (dtoClass != null && dtoClass != Void.class) {
        content.put("application/json", new JsonObject()
          .put("schema", schemaForClass(dtoClass)));
      }
      return new JsonObject()
        .put("required", true)
        .put("content", content);
    }

    final var dtoClass = description.requestBodyClass();
    if (dtoClass == null || dtoClass == Void.class) {
      return null;
    }

    if (dtoClass == String.class) {
      return new JsonObject()
        .put("required", true)
        .put("content", new JsonObject()
          .put("text/plain", new JsonObject()
            .put("schema", new JsonObject().put("type", "string"))));
    }

    final var formSchema = new JsonObject()
      .put("type", "object")
      .put("additionalProperties", true);

    final var jsonMediaType = new JsonObject().put("schema", schemaForClass(dtoClass));
    final var examples = collectExamples(dtoClass);
    if (!examples.isEmpty()) {
      jsonMediaType.put("example", examples.getFirst());
    }

    return new JsonObject()
      .put("required", true)
      .put("content", new JsonObject()
        .put("application/json", jsonMediaType)
        .put("application/x-www-form-urlencoded", new JsonObject().put("schema", formSchema)));
  }

  private static JsonObject buildResponses(RouteDescription description) {
    final var responses = new JsonObject();

    if (description.responseDTOs() == null || description.responseDTOs().isEmpty()) {
      responses.put("200", new JsonObject().put("description", "Successful operation"));
      return responses;
    }

    description.responseDTOs().forEach((code, dto) -> {
      final var typeName = dto instanceof RouteDescription.ResponseCasing casing
        ? casing.innerTypeName()
        : dto.getClass().getSimpleName();

      final var mediaType = new JsonObject()
        .put("example", parseExample(dto.toExample()));

      final var schema = responseSchema(dto);
      if (schema != null) {
        mediaType.put("schema", schema);
      }

      responses.put(String.valueOf(code), new JsonObject()
        .put("description", "%s (%s)".formatted(
          code >= 400 ? "Error response" : "Successful operation", nonNull(typeName)))
        .put("content", new JsonObject()
          .put("application/json", mediaType)));
    });

    return responses;
  }

  private static JsonObject responseSchema(DocumentableDTO dto) {
    if (dto instanceof RouteDescription.ResponseCasing casing) {
      final var dataClass = resolvableClass(casing.data());
      if (dataClass == null) {
        return null;
      }

      return new JsonObject()
        .put("type", "object")
        .put("properties", new JsonObject()
          .put("status", new JsonObject().put("type", "string"))
          .put("message", new JsonObject().put("type", "string"))
          .put("data", schemaForClass(dataClass)));
    }

    final var clazz = resolvableClass(dto);
    return clazz == null ? null : schemaForClass(clazz);
  }

  private static Class<?> resolvableClass(DocumentableDTO dto) {
    if (dto == null) {
      return null;
    }

    final var clazz = dto.getClass();
    if (clazz.isSynthetic() || clazz.isAnonymousClass()) {
      return null;
    }
    if (clazz.getSimpleName().isBlank() || clazz.getSimpleName().startsWith("MissingExample")) {
      return null;
    }
    return clazz;
  }

  private static List<Object> collectExamples(Class<?> dtoClass) {
    final var examples = new ArrayList<>();
    if (dtoClass == null) {
      return examples;
    }

    try {
      for (Field field : dtoClass.getDeclaredFields()) {
        if (!field.isAnnotationPresent(ResponseExample.class)) {
          continue;
        }
        if (!Modifier.isStatic(field.getModifiers()) || !Modifier.isFinal(field.getModifiers())) {
          continue;
        }
        try {
          field.setAccessible(true);
          Object value = field.get(null);
          if (value instanceof DocumentableDTO documentableDTO) {
            examples.add(parseExample(documentableDTO.toExample()));
          } else {
            examples.add(jsonify(value));
          }
        } catch (Exception ignored) {
          // Skip unusable examples and fall through to the next field.
        }
      }
      if (!examples.isEmpty()) {
        return examples;
      }
    } catch (NoClassDefFoundError | SecurityException ignored) {
      // Fall back to the default instance below.
    }

    if (DocumentableDTO.class.isAssignableFrom(dtoClass)) {
      try {
        final var instance = (DocumentableDTO) dtoClass.getDeclaredConstructor().newInstance();
        examples.add(parseExample(instance.toExample()));
      } catch (Exception ignored) {
        // Default instantiation is not always possible; that is acceptable.
      }
    }

    return examples;
  }

  private static Object jsonify(Object value) {
    try {
      final String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value);
      return parseExample(json);
    } catch (Exception ignored) {
      return new JsonObject().put("note", String.valueOf(value));
    }
  }

  private static Object parseExample(String raw) {
    if (raw == null || raw.isBlank()) {
      return new JsonObject().put("note", "No example available");
    }

    final var trimmed = raw.trim();
    try {
      if (trimmed.startsWith("[")) {
        return new JsonArray(trimmed);
      }
      return new JsonObject(trimmed);
    } catch (Exception ignored) {
      return new JsonObject()
        .put("note", "Could not render example")
        .put("raw", trimmed);
    }
  }

  private static Map<String, List<DocumentationRegistrant.RegisteredRoute>> groupRoutes(
    DocumentationRegistrant registrant) {
    final var grouped = new TreeMap<String, List<DocumentationRegistrant.RegisteredRoute>>();
    final var routes = new ArrayList<>(registrant.getRegisteredRoutes());
    routes.sort(Comparator.comparing(DocumentationRegistrant.RegisteredRoute::path)
      .thenComparing(DocumentationRegistrant.RegisteredRoute::method));

    for (DocumentationRegistrant.RegisteredRoute route : routes) {
      final var description = route.description();
      if (description == null) {
        continue;
      }
      if (DOCUMENTATION_CONTROLLER.equals(route.controllerClass())) {
        continue;
      }
      grouped.computeIfAbsent(description.group(), ignored -> new ArrayList<>()).add(route);
    }

    return grouped;
  }

  private static boolean routeRegistersAuthentication(DocumentationRegistrant registrant) {
    return registrant.getRegisteredRoutes().stream()
      .map(DocumentationRegistrant.RegisteredRoute::description)
      .anyMatch(description -> description != null && description.authenticationRequired());
  }

  private static JsonObject resolveComponents(DocumentationRegistrant registrant) {
    final var authSettings = registrant.getAuthSettings();
    if ((authSettings == null || authSettings.isEmpty()) && !routeRegistersAuthentication(registrant)) {
      return null;
    }

    String headerName = "Authorization";
    if (authSettings != null && !authSettings.isEmpty()) {
      final var firstKey = authSettings.keySet().iterator().next();
      if (firstKey != null && !firstKey.isBlank()) {
        headerName = firstKey;
      }
    }

    return new JsonObject()
      .put("securitySchemes", new JsonObject()
        .put("authentication", new JsonObject()
          .put("type", "apiKey")
          .put("in", "header")
          .put("name", headerName)
          .put("description", buildAuthenticationDescription(authSettings))));
  }

  private static String buildAuthenticationDescription(Map<String, String> authSettings) {
    if (authSettings == null || authSettings.isEmpty()) {
      return "The endpoint requires the service authentication header.";
    }

    final var lines = new ArrayList<String>();
    authSettings.forEach((key, value) -> lines.add(String.format("`%s`: `%s`", key, value)));
    return String.join(", ", lines);
  }

  private static String securitySchemeKey(JsonObject components) {
    if (components == null) {
      return null;
    }
    final var schemes = components.getJsonObject("securitySchemes");
    if (schemes == null || schemes.isEmpty()) {
      return null;
    }
    return schemes.fieldNames().iterator().next();
  }

  private static JsonArray buildGroupDescriptionsExtension(DocumentationRegistrant registrant) {
    final var groups = new JsonArray();
    registrant.getGroupDescriptions().forEach((name, description) -> {
      if (description == null || description.isBlank() || "N/A".equals(description)) {
        return;
      }
      groups.add(new JsonObject()
        .put("name", name)
        .put("description", description));
    });
    return groups;
  }

  private static Integer effectiveRateLimit(RouteDescription description) {
    try {
      return description.rateLimit();
    } catch (Exception ignored) {
      return null;
    }
  }

  private static String openApiPath(String path) {
    if (path == null || path.isBlank()) {
      return "/";
    }
    return path.replaceAll(":([A-Za-z0-9_]+)", "{$1}");
  }

  private static String operationId(String name, String path) {
    final var base = sanitizeIdentifier(name);
    final var id = base.isBlank() ? sanitizeIdentifier(path) : base;
    return id.isBlank() ? "operation" : id;
  }

  private static String sanitizeIdentifier(String value) {
    if (value == null) {
      return "";
    }

    final var sanitized = value.trim()
      .replaceAll("[^A-Za-z0-9._-]", "_")
      .replaceAll("_{2,}", "_");

    if (sanitized.isEmpty()) {
      return "";
    }
    return Character.isDigit(sanitized.charAt(0)) ? "_" + sanitized : sanitized;
  }

  private static String nonNull(String value) {
    return value == null ? "" : value;
  }

  /**
   * Derives an OpenAPI 3.1 schema for the given type, expanding enum types into
   * their full set of allowed values so consumers do not lose the alternative
   * options that a lone example value would hide.
   *
   * @param clazz the type to describe.
   * @return a schema {@link JsonObject}.
   */
  static JsonObject schemaForClass(Class<?> clazz) {
    return schemaForClass(clazz, new HashSet<>());
  }

  private static JsonObject schemaForClass(Class<?> clazz, Set<Class<?>> resolving) {
    if (clazz == null || clazz == Object.class) {
      return new JsonObject();
    }

    if (clazz.isEnum()) {
      return enumSchema(clazz);
    }

    final var scalar = scalarSchema(clazz);
    if (scalar != null) {
      return scalar;
    }

    if (clazz.isArray()) {
      return new JsonObject()
        .put("type", "array")
        .put("items", schemaForType(clazz.getComponentType(), resolving));
    }

    if (Collection.class.isAssignableFrom(clazz)) {
      return new JsonObject()
        .put("type", "array")
        .put("items", new JsonObject());
    }

    if (Map.class.isAssignableFrom(clazz) || JsonObject.class.isAssignableFrom(clazz)) {
      return new JsonObject()
        .put("type", "object")
        .put("additionalProperties", new JsonObject());
    }

    if (JsonArray.class.isAssignableFrom(clazz)) {
      return new JsonObject()
        .put("type", "array")
        .put("items", new JsonObject());
    }

    if (clazz.isInterface() || Modifier.isAbstract(clazz.getModifiers())) {
      return new JsonObject();
    }

    // Cycle guard: nested beans that reference each other must not recurse forever.
    if (!resolving.add(clazz)) {
      return new JsonObject();
    }

    try {
      final var properties = new JsonObject();
      final var required = new JsonArray();

      for (Class<?> current = clazz; current != null && current != Object.class; current = current.getSuperclass()) {
        for (Field field : current.getDeclaredFields()) {
          if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
            continue;
          }
          properties.put(propertyName(field), schemaForType(field.getGenericType(), resolving));
          if (field.isAnnotationPresent(NotNull.class) || field.isAnnotationPresent(jakarta.validation.constraints.NotNull.class)) {
            required.add(propertyName(field));
          }
        }
      }

      final var schema = new JsonObject().put("type", "object");
      if (properties.isEmpty()) {
        schema.put("additionalProperties", true);
      } else {
        schema.put("properties", properties);
      }
      if (!required.isEmpty()) {
        schema.put("required", required);
      }
      return schema;
    } finally {
      resolving.remove(clazz);
    }
  }

  private static JsonObject schemaForType(Type type, Set<Class<?>> resolving) {
    if (type instanceof ParameterizedType parameterizedType) {
      final var raw = parameterizedType.getRawType();
      final var arguments = parameterizedType.getActualTypeArguments();

      if (raw instanceof Class<?> rawClass) {
        if (Collection.class.isAssignableFrom(rawClass)) {
          final var item = arguments.length > 0 ? schemaForType(arguments[0], resolving) : new JsonObject();
          return new JsonObject().put("type", "array").put("items", item);
        }
        if (Map.class.isAssignableFrom(rawClass)) {
          final var value = arguments.length > 1 ? schemaForType(arguments[1], resolving) : new JsonObject();
          return new JsonObject().put("type", "object").put("additionalProperties", value);
        }
        if (Optional.class.isAssignableFrom(rawClass)) {
          return arguments.length > 0 ? schemaForType(arguments[0], resolving) : new JsonObject();
        }
      }

      return schemaForType(raw, resolving);
    }

    if (type instanceof GenericArrayType genericArrayType) {
      return new JsonObject()
        .put("type", "array")
        .put("items", schemaForType(genericArrayType.getGenericComponentType(), resolving));
    }

    if (type instanceof WildcardType wildcardType) {
      final var upperBounds = wildcardType.getUpperBounds();
      return upperBounds.length > 0 ? schemaForType(upperBounds[0], resolving) : new JsonObject();
    }

    if (type instanceof Class<?> clazz) {
      return schemaForClass(clazz, resolving);
    }

    return new JsonObject();
  }

  private static JsonObject enumSchema(Class<?> enumClass) {
    final var members = new JsonArray();
    for (Object constant : enumClass.getEnumConstants()) {
      if (constant != null) {
        members.add(serializedEnumMember(constant));
      }
    }

    final var schema = new JsonObject().put("type", "string");
    if (!members.isEmpty()) {
      schema.put("enum", members);
    }
    return schema;
  }

  private static String serializedEnumMember(Object constant) {
    try {
      final var node = MAPPER.readTree(MAPPER.writeValueAsString(constant));
      if (node != null && node.isTextual()) {
        return node.asText();
      }
    } catch (Exception ignored) {
      // Fall back to the constant name below.
    }
    return constant instanceof Enum<?> e ? e.name() : String.valueOf(constant);
  }

  private static String propertyName(Field field) {
    final var annotation = field.getAnnotation(JsonProperty.class);
    if (annotation != null && annotation.value() != null && !annotation.value().isBlank()) {
      return annotation.value();
    }
    return field.getName();
  }

  private static JsonObject scalarSchema(Class<?> clazz) {
    if (clazz == String.class || clazz == Character.class || clazz == char.class || clazz == CharSequence.class) {
      return new JsonObject().put("type", "string");
    }
    if (clazz == UUID.class) {
      return new JsonObject().put("type", "string").put("format", "uuid");
    }
    if (clazz == LocalDate.class) {
      return new JsonObject().put("type", "string").put("format", "date");
    }
    if (clazz == LocalTime.class) {
      return new JsonObject().put("type", "string").put("format", "time");
    }
    if (clazz == LocalDateTime.class
      || clazz == OffsetDateTime.class
      || clazz == ZonedDateTime.class
      || clazz == Instant.class
      || clazz == Date.class) {
      return new JsonObject().put("type", "string").put("format", "date-time");
    }
    if (clazz == BigDecimal.class
      || clazz == Float.class
      || clazz == float.class
      || clazz == Double.class
      || clazz == double.class) {
      return new JsonObject().put("type", "number");
    }
    if (clazz == BigInteger.class
      || clazz == Byte.class
      || clazz == byte.class
      || clazz == Short.class
      || clazz == short.class
      || clazz == Integer.class
      || clazz == int.class
      || clazz == Long.class
      || clazz == long.class) {
      return new JsonObject().put("type", "integer");
    }
    if (clazz == Boolean.class || clazz == boolean.class) {
      return new JsonObject().put("type", "boolean");
    }
    return null;
  }
}
