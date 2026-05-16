package io.github.the_infinite.core.response;

import io.github.the_infinite.core.utils.DataHelpers;

@SuppressWarnings("unused")
public class ServiceResult<T> extends TypedServiceResult<T> {
  protected final String status;

  private boolean naked;

  public ServiceResult(String status, String message, T data, int code) {
    super(ResponseType.JSON, message, data, code);
    this.status = status;
    this.naked = false;
  }

  public String getStatus() {
    return status;
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
    } catch (Throwable e) {
      throw new RuntimeException(
        "Prefer using explicit DTOs with proper contracts with explicit mapping for serialization",
        e
      );
    }
  }

  private record SerializeObject<T>(String status, String message, T data) {}
}
