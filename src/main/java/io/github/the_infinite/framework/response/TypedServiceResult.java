package io.github.the_infinite.framework.response;

@SuppressWarnings("unused")
public abstract class TypedServiceResult<T> {
  protected final String message;
  protected final T data;
  protected final int code;
  protected final ResponseType responseType;

  public TypedServiceResult(ResponseType responseType, String message, T data, int code) {
    this.message = message;
    this.data = data;
    this.code = code;
    this.responseType = responseType;
  }

  public ResponseType getResponseType() {
    return responseType;
  }

  public String getMessage() {
    return message;
  }

  public int getCode() {
    return code;
  }

  public T getData() {
    return data;
  }

  public abstract String serialize();

  public enum ResponseType {
    FILE, JSON, TEXT, XML, JAVASCRIPT, HTML,
  }
}
