package io.github.the_infinite.framework.doc;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.annotation.JsonValue;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

@SuppressWarnings({"unused", "unchecked"})
class OpenApi3GeneratorTest {

  enum Status {
    PENDING, ACTIVE, DISABLED
  }

  enum DeliveryChannel {
    EMAIL("email"),
    PUSH("push"),
    SMS("sms");

    private final String code;

    DeliveryChannel(String code) {
      this.code = code;
    }

    @JsonValue
    public String code() {
      return code;
    }
  }

  @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
  static class Notification {
    private String recipient;
    private Status status;
    private DeliveryChannel channel;
    private List<Status> transitions;
    private Optional<DeliveryChannel> fallbackChannel;
  }

  static class Payload implements DocumentableDTO {
    private String value;

    @Override
    public String toExample() {
      return new JsonObject().put("value", "example").encode();
    }
  }

  static class Envelope<T> {
    private T data;
    private List<T> entries;
  }

  static class GenericPayload implements DocumentableDTO {
    private Envelope<Payload> payload;
    private List<Payload> listPayloads;
    private Set<Payload> setPayloads;

    @Override public String toExample() { return "{}"; }
  }

  static class GenericBase<T> { private T inherited; }
  static class GenericChild extends GenericBase<Payload> implements DocumentableDTO {
    @Override public String toExample() { return "{}"; }
  }

  static class PayloadList extends java.util.ArrayList<Payload> { }
  static class WrappedSet<T> extends HashSet<List<T>> { }
  static class CollectionSubclassPayload implements DocumentableDTO {
    private PayloadList directItems;
    private WrappedSet<Payload> nestedItems;
    @Override public String toExample() { return "{}"; }
  }

  static class RecursiveBox<T> { private T value; private List<RecursiveBox<T>> children; }
  static class RecursiveBoxPayload implements DocumentableDTO {
    private RecursiveBox<Payload> box;
    private java.util.Map<String, List<Payload>> grouped;
    @Override public String toExample() { return "{}"; }
  }

  static class ResponseExamplePayload implements DocumentableDTO {
    @Override public String toExample() { return "{}"; }
  }

  static class ResponseExampleParent implements DocumentableDTO {
    @ResponseExample static final ResponseExamplePayload EXAMPLE = new ResponseExamplePayload();
    private String parentField;
    @Override public String toExample() { return "{\"parentField\":\"example\"}"; }
  }

  static class First { static class SameName implements DocumentableDTO { @Override public String toExample() { return "{}"; } } }
  static class Second { static class SameName implements DocumentableDTO { @Override public String toExample() { return "{}"; } } }

  static class CollidingPayload implements DocumentableDTO {
    private First.SameName first;
    private Second.SameName second;
    @Override public String toExample() { return "{}"; }
  }

  @Test
  void enumFieldsExposeTheFullSetOfValues() {
    final var schema = OpenApi3Generator.schemaForClass(Notification.class);
    final var properties = schema.getJsonObject("properties");

    assertEquals("object", schema.getString("type"));
    assertEquals(List.of("PENDING", "ACTIVE", "DISABLED"), enumMembers(properties, "status"));
    assertEquals(List.of("email", "push", "sms"), enumMembers(properties, "channel"));

    final var transitions = properties.getJsonObject("transitions");
    assertEquals("array", transitions.getString("type"));
    assertEquals(List.of("PENDING", "ACTIVE", "DISABLED"), enumMembers(transitions.getJsonObject("items")));

    assertEquals(List.of("email", "push", "sms"), enumMembers(properties.getJsonObject("fallbackChannel")));
  }

  @Test
  void bareEnumClassProducesAStringEnumSchema() {
    final var schema = OpenApi3Generator.schemaForClass(Status.class);

    assertEquals("string", schema.getString("type"));
    assertTrue(schema.getJsonArray("enum").getList().containsAll(List.of("PENDING", "ACTIVE", "DISABLED")));
  }

  @Test
  void jsonValueDecodedEnumsKeepTheirSerializedValue() {
    final var schema = OpenApi3Generator.schemaForClass(DeliveryChannel.class);

    assertEquals("string", schema.getString("type"));
    assertEquals(List.of("email", "push", "sms"), schema.getJsonArray("enum").getList());
  }

  @Test
  void generatedDocumentsReferenceSchemaRequestBodyAndResponseComponents() {
    final var registrant = DocumentationRegistrant.getInstance();
    final var description = RouteDescription.builder()
      .group("component-test")
      .name("component-test-operation")
      .requestBodyClass(Payload.class)
      .addResponseDTO(200, Payload.class)
      .build();
    registrant.registerRoute("/__openapi_component_test", "POST", "ComponentTestController", description);

    final var spec = OpenApi3Generator.generate(registrant);
    final var components = spec.getJsonObject("components");
    assertNotNull(components);
    final var payloadName = schemaKey(components.getJsonObject("schemas"), Payload.class);

    final var operation = spec.getJsonObject("paths")
      .getJsonObject("/__openapi_component_test")
      .getJsonObject("post");
    final var requestBodyName = referenceName(operation.getJsonObject("requestBody"));
    final var responseName = referenceName(operation.getJsonObject("responses").getJsonObject("200"));
    assertNotNull(components.getJsonObject("requestBodies").getJsonObject(requestBodyName));
    assertNotNull(components.getJsonObject("responses").getJsonObject(responseName));

    final var requestSchema = components.getJsonObject("requestBodies")
      .getJsonObject(requestBodyName)
      .getJsonObject("content").getJsonObject("application/json")
      .getJsonObject("schema");
    assertEquals("#/components/schemas/" + payloadName, requestSchema.getString("$ref"));
  }

  @Test
  void componentsPreserveGenericArgumentsExampleParentsAndNestedClassIdentity() {
    final var registrant = DocumentationRegistrant.getInstance();
    registrant.registerRoute("/__openapi_generic_test", "POST", "GenericTestController", RouteDescription.builder()
      .group("component-test").name("generic-test").requestBodyClass(GenericPayload.class)
      .addResponseDTO(200, ResponseExampleParent.class).build());
    registrant.registerRoute("/__openapi_collision_test", "POST", "CollisionTestController", RouteDescription.builder()
      .group("component-test").name("collision-test").requestBodyClass(CollidingPayload.class).build());
    registrant.registerRoute("/__openapi_inherited_generic_test", "POST", "InheritedGenericTestController", RouteDescription.builder()
      .group("component-test").name("inherited-generic-test").requestBodyClass(GenericChild.class).build());
    registrant.registerRoute("/__openapi_collection_subclass_test", "POST", "CollectionSubclassTestController", RouteDescription.builder()
      .group("component-test").name("collection-subclass-test").requestBodyClass(CollectionSubclassPayload.class).build());
    registrant.registerRoute("/__openapi_recursive_generic_test", "POST", "RecursiveGenericTestController", RouteDescription.builder()
      .group("component-test").name("recursive-generic-test").requestBodyClass(RecursiveBoxPayload.class).build());

    final var schemas = OpenApi3Generator.generate(registrant).getJsonObject("components").getJsonObject("schemas");
    final var envelopeName = schemas.fieldNames().stream().filter(name -> name.contains("EnvelopeOf") && name.contains("Payload_"))
      .findFirst().orElseThrow();
    final var payloadName = schemaKey(schemas, Payload.class);
    final var genericPayloadName = schemaKey(schemas, GenericPayload.class);
    final var envelope = schemas.getJsonObject(envelopeName);
    assertNotNull(envelope);
    assertEquals("#/components/schemas/" + payloadName,
      envelope.getJsonObject("properties").getJsonObject("data").getString("$ref"));
    assertCollectionItemsReferencePayload(schemas, payloadName,
      envelope.getJsonObject("properties").getJsonObject("entries"));
    assertCollectionItemsReferencePayload(schemas, payloadName, schemas.getJsonObject(genericPayloadName)
      .getJsonObject("properties").getJsonObject("listPayloads"));
    assertCollectionItemsReferencePayload(schemas, payloadName, schemas.getJsonObject(genericPayloadName)
      .getJsonObject("properties").getJsonObject("setPayloads"));
    assertNotNull(schemas.getJsonObject(schemaKey(schemas, First.SameName.class)));
    assertNotNull(schemas.getJsonObject(schemaKey(schemas, Second.SameName.class)));
    assertEquals("#/components/schemas/" + payloadName, schemas.getJsonObject(schemaKey(schemas, GenericChild.class))
      .getJsonObject("properties").getJsonObject("inherited").getString("$ref"));
    final var collectionSubclass = schemas.getJsonObject(schemaKey(schemas, CollectionSubclassPayload.class)).getJsonObject("properties");
    assertCollectionItemsReferencePayload(schemas, payloadName, collectionSubclass.getJsonObject("directItems"));
    final var nestedCollection = schemas.getJsonObject(referenceName(collectionSubclass.getJsonObject("nestedItems")));
    assertCollectionItemsReferencePayload(schemas, payloadName, nestedCollection.getJsonObject("items"));
    final var recursivePayload = schemas.getJsonObject(schemaKey(schemas, RecursiveBoxPayload.class)).getJsonObject("properties");
    final var recursiveBox = schemas.getJsonObject(referenceName(recursivePayload.getJsonObject("box")));
    assertEquals("#/components/schemas/" + payloadName, recursiveBox.getJsonObject("properties")
      .getJsonObject("value").getString("$ref"));
    assertEquals(referenceName(recursivePayload.getJsonObject("box")), referenceName(recursiveBox.getJsonObject("properties")
      .getJsonObject("children").getJsonObject("items")));
    assertCollectionItemsReferencePayload(schemas, payloadName, recursivePayload.getJsonObject("grouped")
      .getJsonObject("additionalProperties"));

    final var parentName = schemaKey(schemas, ResponseExampleParent.class);
    final var responses = OpenApi3Generator.generate(registrant).getJsonObject("components").getJsonObject("responses");
    final var response = responses.getJsonObject(responses.fieldNames().stream()
      .filter(name -> name.contains(parentName)).findFirst().orElseThrow());
    assertEquals("#/components/schemas/" + parentName,
      response.getJsonObject("content").getJsonObject("application/json").getJsonObject("schema")
        .getJsonObject("properties").getJsonObject("data").getString("$ref"));
  }

  private static void assertCollectionItemsReferencePayload(JsonObject schemas, String payloadName, JsonObject collectionReference) {
    final var schema = collectionReference.containsKey("$ref")
      ? schemas.getJsonObject(referenceName(collectionReference))
      : collectionReference;
    assertEquals("#/components/schemas/" + payloadName,
      schema.getJsonObject("items").getString("$ref"));
  }

  private static String referenceName(JsonObject reference) {
    return reference.getString("$ref").substring(reference.getString("$ref").lastIndexOf('/') + 1);
  }

  private static String schemaKey(JsonObject components, Class<?> type) {
    final var suffix = type.getSimpleName() + "_";
    return components.fieldNames().stream().filter(name -> name.endsWith(suffix + Integer.toUnsignedString(type.getName().hashCode(), 36)))
      .findFirst().orElseThrow();
  }

  private static List<Object> enumMembers(JsonObject schema, String... pathToEnum) {
    JsonObject current = schema;
    for (String key : pathToEnum) {
      current = current.getJsonObject(key);
    }
    JsonArray members = current.getJsonArray("enum");
    return members == null ? List.of() : members.getList();
  }
}
