package io.github.the_infinite.framework.response;

import io.github.the_infinite.framework.utils.DataHelpers;
import lombok.Getter;

@SuppressWarnings("unused")
public class ServiceResult<T> extends TypedServiceResult<T> {
  @Getter
  protected final String status;

  private boolean naked;

  public ServiceResult(String status, String message, T data, int code) {
    super(ResponseType.JSON, message, data, code);
    this.status = status;
    this.naked = false;
  }

  public ServiceResult<T> stripped() {
    this.naked = true;
    return this;
  }

  @Override
  public String serialize() {
    try {
      //? If this is a naked response, return data as is
      if (naked) {
        return DataHelpers.serializeObject(data);
      }

      //? If it is not a naked response, wrap it in a JSON object
      return DataHelpers.serializeObject(new SerializeObject<>(status, message, data));
    } catch (Exception e) {
      throw new IllegalStateException(
        "Failed to serialize the service result. Prefer explicit DTOs with stable serialization contracts.",
        e
      );
    }
  }

  private record SerializeObject<T>(String status, String message, T data) {}
}
