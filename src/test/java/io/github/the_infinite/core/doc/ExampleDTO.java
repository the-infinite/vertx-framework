package io.github.the_infinite.core.doc;

import com.fasterxml.jackson.core.JsonProcessingException;

import io.github.the_infinite.core.utils.DataHelpers;

import io.vertx.core.json.JsonObject;

public class ExampleDTO implements DocumentableDTO {
  @ResponseExample("Success Example")
  public static final ExampleDTO SUCCESS = new ExampleDTO("success", 201, "hello");
  @ResponseExample("Error Example")
  public static final ExampleDTO ERROR = new ExampleDTO("error", null, "an error occurred");
  private final String status;
  private final Object data;
  private final String message;

  public ExampleDTO(String status, Object data, String message) {
    this.status = status;
    this.data = data;
    this.message = message;
  }

  @Override
  public String toExample() {
    try {
      return new JsonObject(DataHelpers.serializeObject(this)).encodePrettily();
    } catch (JsonProcessingException e) {
      throw new RuntimeException(e);
    }
  }
}
