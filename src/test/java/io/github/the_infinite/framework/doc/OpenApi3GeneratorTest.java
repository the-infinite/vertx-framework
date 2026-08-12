package io.github.the_infinite.framework.doc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonValue;
import org.junit.jupiter.api.Test;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.List;
import java.util.Optional;

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

  static class Notification {
    private String recipient;
    private Status status;
    private DeliveryChannel channel;
    private List<Status> transitions;
    private Optional<DeliveryChannel> fallbackChannel;
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

  private static List<Object> enumMembers(JsonObject schema, String... pathToEnum) {
    JsonObject current = schema;
    for (String key : pathToEnum) {
      current = current.getJsonObject(key);
    }
    JsonArray members = current.getJsonArray("enum");
    return members == null ? List.of() : members.getList();
  }
}