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
import lombok.extern.slf4j.Slf4j;

/**
 * Produces a valid <a href="https://spec.openapis.org/oas/v3.1.0.html">OpenAPI 3.1</a>
 * specification document from the routes and settings registered in the
 * {@link DocumentationRegistrant}.
 *
 * <p>The generated document is consumed by the Swagger UI static distribution that is
 * served by the {@link DocumentationController}, so it deliberately favours
 * <code>example</code> rich payloads over hand-built schema models.</p>
 */
@SuppressWarnings("unused")
@Slf4j
public final class OpenApi3Generator {
  private static final String OPENAPI_VERSION = "3.1.0";
  private static final String DOCUMENTATION_CONTROLLER = "DocumentationController";
  private static final ObjectMapper MAPPER = new ObjectMapper();
  /** Components are scoped to one generated document; keeping these globally leaks
   * types between applications and makes concurrent generation unsafe. */
  private static final ThreadLocal<ComponentRegistry> ACTIVE_COMPONENTS = new ThreadLocal<>();
  private static final ThreadLocal<Set<Class<?>>> INLINE_COMPONENT_DEFINITIONS = new ThreadLocal<>();
  private static final Set<String> RESERVED_HEADER_PARAMETERS = Set.of(
    "accept", "content-type", "authorization"
  );

  private OpenApi3Generator() {
  }

  private static String componentName(Class<?> clazz) {
    return shortTypeName(clazz.getName(), displayName(clazz));
  }

  private static String componentName(Class<?> clazz, Type type) {
    return componentName(clazz) + "__" + componentName(type);
  }

  private static String componentName(Type type) {
    if (type instanceof Class<?> clazz) return componentName(clazz);
    if (type instanceof ParameterizedType parameterizedType) {
      final var display = displayName(parameterizedType.getRawType()) + "Of" +
        Arrays.stream(parameterizedType.getActualTypeArguments())
          .map(OpenApi3Generator::displayName)
          .collect(java.util.stream.Collectors.joining("And"));
      return shortTypeName(typeIdentity(parameterizedType), display);
    }
    if (type instanceof GenericArrayType arrayType)
      return componentName(arrayType.getGenericComponentType()) + "__array";
    if (type instanceof TypeVariable<?> variable) return "type__" + variable.getName();
    if (type instanceof WildcardType wildcard) {
      final var bounds = wildcard.getUpperBounds();
      return bounds.length == 0 ? "object" : componentName(bounds[0]);
    }
    return shortTypeName(type.getTypeName(), "Type");
  }

  private static String displayName(Type type) {
    if (type instanceof Class<?> clazz) return displayName(clazz);
    if (type instanceof ParameterizedType parameterizedType) {
      return displayName(parameterizedType.getRawType()) + "Of" + Arrays.stream(parameterizedType.getActualTypeArguments())
        .map(OpenApi3Generator::displayName).collect(java.util.stream.Collectors.joining("And"));
    }
    if (type instanceof TypeVariable<?> variable) return variable.getName();
    return "Value";
  }

  private static String displayName(Class<?> clazz) {
    final var chain = new ArrayDeque<String>();
    for (Class<?> current = clazz; current != null; current = current.getEnclosingClass()) chain.addFirst(current.getSimpleName());
    return String.join(".", chain);
  }

  private static String shortTypeName(String identity, String display) {
    // OpenAPI has no schema alias that Swagger UI uses consistently. A short,
    // stable suffix retains the fully qualified identity without exposing it.
    return display.replaceAll("[^A-Za-z0-9_.]", "_") + "_" + Integer.toUnsignedString(identity.hashCode(), 36);
  }

  /** A stable identity shared by reflection and our substituted Type instances. */
  private static String typeIdentity(Type type) {
    if (type instanceof Class<?> clazz) return clazz.getName();
    if (type instanceof ParameterizedType parameterizedType) {
      return typeIdentity(parameterizedType.getRawType()) + "<" +
        Arrays.stream(parameterizedType.getActualTypeArguments())
          .map(OpenApi3Generator::typeIdentity)
          .collect(java.util.stream.Collectors.joining(",")) + ">";
    }
    if (type instanceof GenericArrayType arrayType) return typeIdentity(arrayType.getGenericComponentType()) + "[]";
    if (type instanceof TypeVariable<?> variable) {
      return variable.getGenericDeclaration().toString() + ":" + variable.getName();
    }
    if (type instanceof WildcardType wildcard) {
      return "?extends" + Arrays.stream(wildcard.getUpperBounds())
        .map(OpenApi3Generator::typeIdentity).collect(java.util.stream.Collectors.joining("&"));
    }
    return type.getTypeName();
  }

  /**
   * Builds the complete OpenAPI 3.1 document for the given registrant.
   *
   * @param registrant the documentation registry holding all metadata.
   * @return the OpenAPI specification as a {@link JsonObject}.
   */
  public static JsonObject generate(DocumentationRegistrant registrant) {
    ACTIVE_COMPONENTS.set(new ComponentRegistry());
    try {
      final var spec = new JsonObject()
        .put("openapi", OPENAPI_VERSION)
        .put("info", buildInfo(registrant))
        .put("servers", buildServers())
        .put("paths", buildPaths(registrant));

      final var components = resolveComponents(registrant);
      if (!components.isEmpty()) {
        spec.put("components", components);
      }
      if (securitySchemeKey(components) != null) {
        spec.put("security", new JsonArray());
      }

      final var groups = buildGroupDescriptionsExtension(registrant);
      if (!groups.isEmpty()) {
        spec.put("x-groups", groups);
      }
      return spec;
    } finally {
      ACTIVE_COMPONENTS.remove();
    }
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

    final var requestBody = buildRequestBody(description, routePath);
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

  private static JsonObject buildRequestBody(RouteDescription description, String routePath) {
    final var requestBody = buildRequestBodyDefinition(description);
    if (requestBody == null) {
      return null;
    }

    final var typeName = description.requestBodyClass() == null
      ? "Multipart"
      : componentName(description.requestBodyClass());
    final var name = description.fileParameters().isEmpty()
      ? "RequestBody<%s>".formatted(typeName)
      : "RequestBody<%s>".formatted(operationId(description.name(), routePath));
    return componentReference("requestBodies", name, requestBody);
  }

  private static JsonObject buildRequestBodyDefinition(RouteDescription description) {
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
          .put("schema", schemaReferenceForClass(dtoClass)));
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

    final var jsonMediaType = new JsonObject().put("schema", schemaReferenceForClass(dtoClass));
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
      responses.put("200", componentReference("responses", "Response<200,Empty>",
        new JsonObject().put("description", "Successful operation")));
      return responses;
    }

    description.responseDTOs().forEach((code, dto) -> {
      final var typeName = responseTypeName(dto);

      final var mediaType = new JsonObject()
        .put("example", parseExample(dto.toExample()));

      final var schema = responseSchema(dto);
      if (schema != null) {
        mediaType.put("schema", schema);
      }

      final var response = new JsonObject()
        .put("description", "%s (%s)".formatted(
          code >= 400 ? "Error response" : "Successful operation", nonNull(typeName)))
        .put("content", new JsonObject()
          .put("application/json", mediaType));
      responses.put(String.valueOf(code), componentReference("responses",
        "Response<%s,%s>".formatted(code, typeName), response));
    });

    return responses;
  }

  private static String responseTypeName(DocumentableDTO dto) {
    if (!(dto instanceof RouteDescription.ResponseCasing casing)) {
      return componentName(dto.getClass());
    }
    if (casing.documentedClass() != null) {
      return componentName(casing.documentedClass());
    }
    return casing.data() == null ? "Response" : componentName(casing.data().getClass());
  }

  private static JsonObject responseSchema(DocumentableDTO dto) {
    if (dto instanceof RouteDescription.ResponseCasing casing) {
      // The builder records the declared response DTO class.  An @ResponseExample
      // field supplies a value only; its field type must not redefine the schema.
      final var dataClass = casing.documentedClass();
      if (dataClass == null) {
        return null;
      }

      return new JsonObject()
        .put("type", "object")
        .put("properties", new JsonObject()
          .put("status", new JsonObject().put("type", "string").put("enum", new JsonArray().add("success").add("error")))
          .put("message", new JsonObject().put("type", "string"))
          .put("data", schemaReferenceForClass(dataClass)));
    }

    final var clazz = resolvableClass(dto);
    return clazz == null ? null : schemaReferenceForClass(clazz);
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
    final var registry = components();
    final var result = registry.toJson();
    if ((authSettings == null || authSettings.isEmpty()) && !routeRegistersAuthentication(registrant)) {
      return result;
    }

    final var authHeaders = registrant.getAuthenticatedGlobalHeaders();

    for (final var header : authHeaders.entrySet()) {
      final var headerName = header.getKey();
      final var description = header.getValue();
      result.put("securitySchemes", new JsonObject()
        .put("authentication", new JsonObject()
          .put("type", "apiKey")
          .put("in", "header")
          .put("name", headerName)
          .put("description", description)));
    }
    return result;
  }

  private static ComponentRegistry components() {
    final var registry = ACTIVE_COMPONENTS.get();
    if (registry == null) {
      throw new IllegalStateException("OpenAPI components are only available while generating a document");
    }
    return registry;
  }

  private static JsonObject componentReference(String section, String name, JsonObject definition) {
    final var entries = components().section(section);
    if (!entries.containsKey(name)) {
      entries.put(name, definition);
    }
    return new JsonObject().put("$ref", "#/components/%s/%s".formatted(section, name));
  }

  private static final class ComponentRegistry {
    private final JsonObject schemas = new JsonObject();
    private final JsonObject requestBodies = new JsonObject();
    private final JsonObject responses = new JsonObject();

    JsonObject section(String name) {
      return switch (name) {
        case "schemas" -> schemas;
        case "requestBodies" -> requestBodies;
        case "responses" -> responses;
        default ->
          throw new IllegalArgumentException("Unsupported component section: " + name);
      };
    }

    JsonObject toJson() {
      final var result = new JsonObject();
      if (!schemas.isEmpty()) result.put("schemas", schemas);
      if (!requestBodies.isEmpty()) result.put("requestBodies", requestBodies);
      if (!responses.isEmpty()) result.put("responses", responses);
      return result;
    }
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

  /** Returns a component reference for a structured type, while scalar schemas stay inline. */
  private static JsonObject schemaReferenceForClass(Class<?> clazz) {
    return schemaReferenceForType(clazz);
  }

  private static JsonObject schemaReferenceForType(Type type) {
    final var clazz = rawClass(type);
    if (!isStructuredType(clazz)) {
      return schemaForType(type, new HashSet<>(), Map.of());
    }

    final var name = componentName(type);
    final var componentSchemas = components().section("schemas");
    if (!componentSchemas.containsKey(name)) {
      for (final var field : clazz.getDeclaredFields()) {
        //! This is a patch to fix a bug. Do not REMOVE!!! If you have any desire to
        //! remove it, ASK ME BEFORE YOU DO SO!
        if (field.isAnnotationPresent(ResponseExample.class) && !field.getType().equals(clazz)) {
          final var fieldType = field.getType();
          componentSchemas.put(name, new JsonObject());
          final var inline = Optional.ofNullable(INLINE_COMPONENT_DEFINITIONS.get()).orElseGet(HashSet::new);
          INLINE_COMPONENT_DEFINITIONS.set(inline);
          inline.add(fieldType);
          try {
            componentSchemas.put(name, schemaForType(fieldType, new HashSet<>(), typeBindings(fieldType)));
          } finally {
            inline.remove(fieldType);
            if (inline.isEmpty()) INLINE_COMPONENT_DEFINITIONS.remove();
          }
          return new JsonObject().put("$ref", "#/components/schemas/" + name);
        }
      }

      // Register a placeholder before resolving fields so self-referential DTOs
      // resolve back to this component rather than recursing indefinitely.
      componentSchemas.put(name, new JsonObject());
      final var inline = Optional.ofNullable(INLINE_COMPONENT_DEFINITIONS.get()).orElseGet(HashSet::new);
      INLINE_COMPONENT_DEFINITIONS.set(inline);
      inline.add(clazz);
      try {
        // Keep the parameterized type here. Replacing it with clazz discards
        // Foo<Bar>'s binding before Foo's fields (for example, List<T>) are read.
        componentSchemas.put(name, schemaForType(type, new HashSet<>(), typeBindings(type)));
      } finally {
        inline.remove(clazz);
        if (inline.isEmpty()) INLINE_COMPONENT_DEFINITIONS.remove();
      }
    }
    return new JsonObject().put("$ref", "#/components/schemas/" + name);
  }

  private static boolean isStructuredType(Class<?> clazz) {
    return clazz != null
      && clazz != Object.class
      && scalarSchema(clazz) == null
      && !clazz.isArray()
      && !Collection.class.isAssignableFrom(clazz)
      && !Map.class.isAssignableFrom(clazz)
      && !JsonObject.class.isAssignableFrom(clazz)
      && !JsonArray.class.isAssignableFrom(clazz)
      && !clazz.isInterface()
      && !Modifier.isAbstract(clazz.getModifiers());
  }

  private static JsonObject schemaForClass(Class<?> clazz, Set<Class<?>> resolving) {
    return schemaForType(clazz, resolving, Map.of());
  }

  private static JsonObject schemaForType(Type type, Set<Class<?>> resolving, Map<TypeVariable<?>, Type> bindings) {
    type = resolveType(type, bindings);
    if (type instanceof ParameterizedType parameterizedType) {
      final var raw = parameterizedType.getRawType();
      final var arguments = parameterizedType.getActualTypeArguments();
      if (raw instanceof Class<?> rawClass) {
        if (Collection.class.isAssignableFrom(rawClass)) {
          final var item = collectionElementType(parameterizedType, bindings);
          return collectionSchema(parameterizedType, item, resolving, bindings);
        }
        if (Map.class.isAssignableFrom(rawClass)) {
          final var value = arguments.length > 1 ? arguments[1] : Object.class;
          return new JsonObject().put("type", "object")
            .put("additionalProperties", schemaForType(value, resolving, bindings));
        }
        if (Optional.class.isAssignableFrom(rawClass)) {
          return arguments.length == 0 ? new JsonObject() : schemaForType(arguments[0], resolving, bindings);
        }
        if (ACTIVE_COMPONENTS.get() != null && isStructuredType(rawClass)) {
          if (Optional.ofNullable(INLINE_COMPONENT_DEFINITIONS.get()).orElseGet(Set::of).contains(rawClass)) {
            // We are materializing this parameterized component. Continue with
            // the raw class only after retaining its actual type bindings.
            // We are materializing this parameterized component. Continue with
            // the raw class only after retaining its actual type bindings.
            return schemaForType(rawClass, resolving, typeBindings(parameterizedType));
          }
          return schemaReferenceForType(parameterizedType);
        }
        return schemaForType(rawClass, resolving, typeBindings(parameterizedType));
      }
    }
    if (type instanceof GenericArrayType arrayType) {
      return new JsonObject().put("type", "array")
        .put("items", schemaForType(arrayType.getGenericComponentType(), resolving, bindings));
    }
    if (type instanceof WildcardType wildcard) {
      final var lowerBounds = wildcard.getLowerBounds();
      if (lowerBounds.length > 0) return schemaForType(lowerBounds[0], resolving, bindings);
      final var upperBounds = wildcard.getUpperBounds();
      return upperBounds.length == 0 || upperBounds[0] == Object.class
        ? new JsonObject()
        : schemaForType(upperBounds[0], resolving, bindings);
    }
    if (type instanceof TypeVariable<?> variable) {
      final var bound = bindings.get(variable);
      return bound == null ? new JsonObject() : schemaForType(bound, resolving, bindings);
    }
    if (!(type instanceof Class<?> clazz)) {
      log.info("Non class-type {}", type.getTypeName());
      return new JsonObject();
    }
    if (clazz == Object.class) {
      log.info("Object class found, returning empty schema for {}", clazz.getName());
      return new JsonObject();
    }

    if (clazz.isEnum()) {
      return enumSchema(clazz);
    }

    final var scalar = scalarSchema(clazz);
    if (scalar != null) {
      return scalar;
    }

    if (ACTIVE_COMPONENTS.get() != null && isStructuredType(clazz)
      && !Optional.ofNullable(INLINE_COMPONENT_DEFINITIONS.get()).orElseGet(Set::of).contains(clazz)) {
      return schemaReferenceForClass(clazz);
    }

    if (clazz.isArray()) {
      return new JsonObject()
        .put("type", "array")
        .put("items", schemaForType(clazz.getComponentType(), resolving, bindings));
    }

    if (Collection.class.isAssignableFrom(clazz)) {
      log.info("Collection class found, initializing schema for {}", clazz.getName());
      return new JsonObject()
        .put("type", "array")
        .put("items", schemaForType(collectionElementType(clazz, bindings), resolving, bindings));
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
      // The current binding context may describe Node<Payload>, not merely Node.
      // Preserve it in the cycle reference so its fields retain Payload.
      return ACTIVE_COMPONENTS.get() != null
        ? schemaReferenceForType(boundType(clazz, bindings))
        : new JsonObject();
    }

    try {
      final var properties = new JsonObject();
      final var required = new JsonArray();

      var currentBindings = new HashMap<>(bindings);
      for (Class<?> current = clazz; current != null && current != Object.class; ) {
        for (Field field : current.getDeclaredFields()) {
          if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
            continue;
          }
          properties.put(propertyName(field), schemaForType(field.getGenericType(), resolving,
            currentBindings));
          if (field.isAnnotationPresent(NotNull.class) || field.isAnnotationPresent(jakarta.validation.constraints.NotNull.class)) {
            required.add(propertyName(field));
          }
        }
        final var genericSuperclass = current.getGenericSuperclass();
        currentBindings = inheritedTypeBindings(genericSuperclass, currentBindings);
        current = rawClass(genericSuperclass);
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
    return schemaForType(type, resolving, Map.of());
  }

  private static JsonObject collectionSchema(Type collectionType, Type itemType,
                                             Set<Class<?>> resolving, Map<TypeVariable<?>, Type> bindings) {
    if (ACTIVE_COMPONENTS.get() == null) {
      return new JsonObject().put("type", "array").put("items", schemaForType(itemType, resolving, bindings));
    }
    final var key = componentName(collectionType);
    final var schemas = components().section("schemas");
    if (!schemas.containsKey(key)) {
      schemas.put(key, new JsonObject());
      schemas.put(key, new JsonObject().put("type", "array")
        .put("items", schemaForType(itemType, resolving, bindings)));
    }
    return new JsonObject().put("$ref", "#/components/schemas/" + key);
  }

  /**
   * Resolves collection subclasses too (for example, {@code UserList extends
   * ArrayList<User>}), instead of falling back to the raw collection variable
   * {@code E}.  This is also used after a generic superclass binding is applied.
   */
  private static Type collectionElementType(Type type, Map<TypeVariable<?>, Type> bindings) {
    type = resolveType(type, bindings);
    final var clazz = rawClass(type);
    if (clazz == null || !Collection.class.isAssignableFrom(clazz)) return Object.class;

    final var localBindings = new HashMap<>(bindings);
    if (type instanceof ParameterizedType parameterizedType) {
      final var variables = clazz.getTypeParameters();
      final var arguments = parameterizedType.getActualTypeArguments();
      for (int index = 0; index < Math.min(variables.length, arguments.length); index++) {
        localBindings.put(variables[index], resolveType(arguments[index], bindings));
      }
    }

    if (clazz == Collection.class) {
      final var variable = clazz.getTypeParameters()[0];
      return resolveType(localBindings.getOrDefault(variable, Object.class), localBindings);
    }

    for (Type interfaceType : clazz.getGenericInterfaces()) {
      final var candidate = collectionElementType(interfaceType, localBindings);
      if (candidate != Object.class) return candidate;
    }
    final var superclass = clazz.getGenericSuperclass();
    return superclass == null ? Object.class : collectionElementType(superclass, localBindings);
  }

  private static Class<?> rawClass(Type type) {
    if (type instanceof Class<?> clazz) return clazz;
    if (type instanceof ParameterizedType parameterizedType && parameterizedType.getRawType() instanceof Class<?> clazz)
      return clazz;
    return null;
  }

  private static Map<TypeVariable<?>, Type> typeBindings(Type type) {
    if (!(type instanceof ParameterizedType parameterizedType) || !(parameterizedType.getRawType() instanceof Class<?> raw)) {
      return Map.of();
    }
    final var bindings = new HashMap<TypeVariable<?>, Type>();
    final var variables = raw.getTypeParameters();
    final var arguments = parameterizedType.getActualTypeArguments();
    for (int index = 0; index < Math.min(variables.length, arguments.length); index++) {
      bindings.put(variables[index], arguments[index]);
    }
    return bindings;
  }

  private static Type resolveType(Type type, Map<TypeVariable<?>, Type> bindings) {
    if (type instanceof TypeVariable<?> variable)
      return bindings.getOrDefault(variable, variable);
    if (type instanceof ParameterizedType parameterizedType) {
      final var arguments = Arrays.stream(parameterizedType.getActualTypeArguments())
        .map(argument -> resolveType(argument, bindings)).toArray(Type[]::new);
      return new ResolvedParameterizedType((Class<?>) parameterizedType.getRawType(), arguments, parameterizedType.getOwnerType());
    }
    return type;
  }

  private static Type boundType(Class<?> clazz, Map<TypeVariable<?>, Type> bindings) {
    final var variables = clazz.getTypeParameters();
    if (variables.length == 0) return clazz;

    final var arguments = Arrays.stream(variables)
      .map(variable -> resolveType(variable, bindings))
      .toArray(Type[]::new);
    // A raw generic class does not have enough information to claim a concrete
    // parameterized component identity.
    if (Arrays.equals(variables, arguments)) return clazz;
    return new ResolvedParameterizedType(clazz, arguments, clazz.getDeclaringClass());
  }

  private static HashMap<TypeVariable<?>, Type> inheritedTypeBindings(Type superclass,
                                                                      Map<TypeVariable<?>, Type> bindings) {
    final var inherited = new HashMap<TypeVariable<?>, Type>();
    if (!(superclass instanceof ParameterizedType parameterizedType)
      || !(parameterizedType.getRawType() instanceof Class<?> raw)) {
      return inherited;
    }
    final var variables = raw.getTypeParameters();
    final var arguments = parameterizedType.getActualTypeArguments();
    for (int index = 0; index < Math.min(variables.length, arguments.length); index++) {
      inherited.put(variables[index], resolveType(arguments[index], bindings));
    }
    return inherited;
  }

  private record ResolvedParameterizedType(Class<?> rawType, Type[] actualTypeArguments,
                                           Type ownerType) implements ParameterizedType {
    @Override
    public Type @NotNull [] getActualTypeArguments() {
      return actualTypeArguments.clone();
    }

    @Override
    public @NotNull Type getRawType() {
      return rawType;
    }

    @Override
    public Type getOwnerType() {
      return ownerType;
    }
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
