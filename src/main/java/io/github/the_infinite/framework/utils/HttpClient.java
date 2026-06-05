package io.github.the_infinite.framework.utils;

import java.util.Map;

import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;

@SuppressWarnings("unused")
public class HttpClient {
  private final io.vertx.core.http.HttpClient client;
  private final MultiMap baseHeaders = MultiMap.caseInsensitiveMultiMap();

  public HttpClient(Vertx vertx, Map<String, String> headers) {
    this.client = vertx.createHttpClient();
    this.baseHeaders.addAll(headers);
  }

  private String noTrailingSlash(String path) {
    path = path.trim().replaceAll("//+", "/");

    if (path.isEmpty() || path.equals("/")) {
      return "/";
    }

    if (path.endsWith("/")) {
      return path.substring(0, path.lastIndexOf('/'));
    }

    return path;
  }

  private Future<HttpClientResponse> sendRequest(final HttpMethod method, final String path, final Buffer body, final Map<String, String> headers) {
    //? If this is not
    var usedHeaders = baseHeaders.copy();

    //? If this is not null and has some data...
    if (headers != null && !headers.isEmpty()) {
      usedHeaders.addAll(headers);
    }

    return client.request(new RequestOptions()
      .setMethod(method)
      .setHeaders(usedHeaders)
      .setFollowRedirects(true)
      .setAbsoluteURI(path)
    ).compose(request -> {
      if (body != null) {
        return request.send(body);
      }

      return request.send();
    });
  }

  public Future<HttpClientResponse> get(String path, Map<String, String> headers) {
    return sendRequest(HttpMethod.GET, path, null, headers);
  }

  public Future<HttpClientResponse> get(String path) {
    return get(path, null);
  }

  public Future<HttpClientResponse> post(String path, Buffer body, Map<String, String> headers) {
    return sendRequest(HttpMethod.POST, path, body, headers);
  }

  public Future<HttpClientResponse> post(String path, Buffer body) {
    return post(path, body, null);
  }

  public Future<HttpClientResponse> put(String path, Buffer body, Map<String, String> headers) {
    return sendRequest(HttpMethod.PUT, path, body, headers);
  }

  public Future<HttpClientResponse> put(String path, Buffer body) {
    return put(path, body, null);
  }

  public Future<HttpClientResponse> delete(String path, Map<String, String> headers) {
    return sendRequest(HttpMethod.DELETE, path, null, headers);
  }

  public Future<HttpClientResponse> delete(String path) {
    return delete(path, null);
  }

  public Future<HttpClientResponse> patch(String path, Buffer body, Map<String, String> headers) {
    return sendRequest(HttpMethod.PATCH, path, body, headers);
  }

  public Future<HttpClientResponse> patch(String path, Buffer body) {
    return patch(path, body, null);
  }

  public Future<HttpClientResponse> head(String path, Map<String, String> headers) {
    return sendRequest(HttpMethod.HEAD, path, null, headers);
  }

  public Future<HttpClientResponse> head(String path) {
    return head(path, null);
  }

  public Future<HttpClientResponse> options(String path, Map<String, String> headers) {
    return sendRequest(HttpMethod.OPTIONS, path, null, headers);
  }

  public Future<HttpClientResponse> options(String path) {
    return options(path, null);
  }

  public Future<Void> close() {
    return client.close();
  }
}
