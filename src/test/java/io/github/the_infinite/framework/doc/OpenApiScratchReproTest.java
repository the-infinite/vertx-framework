package io.github.the_infinite.framework.doc;

import org.junit.jupiter.api.Test;

class OpenApiScratchReproTest {

  record ScalarData() implements DocumentableDTO {
    @Override
    public String toExample() {
      return "true";
    }
  }

  record ComplexData(String name, ScalarData flag,
                     java.util.List<Item> items) implements DocumentableDTO {
    record Item(String id) implements DocumentableDTO {
      @Override
      public String toExample() {
        return "{\"id\":\"abc\"}";
      }
    }

    @Override
    public String toExample() {
      return "{\"name\":\"demo\",\"flag\":{\"flag\":true},\"items\":[{\"id\":\"abc\"}]}";
    }
  }

  @Test
  void printShapes() {
    System.out.println("=== ScalarData schema ===");
    System.out.println(OpenApi3Generator.schemaForClass(ScalarData.class).encodePrettily());
    System.out.println("=== ComplexData schema ===");
    System.out.println(OpenApi3Generator.schemaForClass(ComplexData.class).encodePrettily());
    System.out.println("=== ResponseCasing schema for data: ScalarData ===");
    final var casing = new RouteDescription.ResponseCasing("success", "ok", new ScalarData());
    final var usedClass= casing.data() == null ? String.class : casing.data().getClass();
    System.out.println(OpenApi3Generator.schemaForClass(usedClass).encodePrettily());
    System.out.println("=== ScalarData runtime class ===");
    System.out.println(usedClass);
  }
}
