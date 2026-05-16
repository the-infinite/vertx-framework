package io.github.the_infinite.core.doc.impl;

import io.github.the_infinite.core.ConfigurationRegistrant;
import io.github.the_infinite.core.doc.HttpClient;

import java.util.HashMap;
import java.util.Map;

import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;

/**
 * A simple HTTP client leveraging Vert.x HTTP client implementation for scalability.
 */
public class ClassicHttpClient implements HttpClient {
  @Override
  public String getName() {
    return "Classic HTTP Client";
  }

  @Override
  public String getDescription() {
    return "A simple HTTP client built leveraging Vert.x HTTP client implementation for scalability.";
  }

  @Override
  public String getUsageSnippet() {
    return """
      WebClient client = WebClient.create(vertx);

      client.get(port, host, path)
            .putHeader("X-Correlation-ID", correlationId)
            .send()
            .onSuccess(response -> {
                System.out.println("Received response with status code " + response.statusCode());
            })
            .onFailure(err -> {
                System.out.println("Something went wrong " + err.getMessage());
            });
      """;
  }

  @Override
  public Future<Response> execute(String method, String url, Map<String, String> headers, String body, Map<String, String> parameters) {
    final var vertx = ConfigurationRegistrant.vertx();
    final var client = vertx.createHttpClient();
    final var httpMethod = HttpMethod.valueOf(method.toUpperCase());

    final var vertxHeaders = MultiMap.caseInsensitiveMultiMap();
    if (headers != null) {
      headers.forEach(vertxHeaders::add);
    }

    return client.request(new RequestOptions()
      .setMethod(httpMethod)
      .setAbsoluteURI(url)
      .setHeaders(vertxHeaders)
    ).compose(request -> {
      if (body != null && !body.isEmpty()) {
        return request.send(Buffer.buffer(body));
      } else {
        return request.send();
      }
    }).compose(response -> response.body().map(buffer -> {
      Map<String, String> respHeaders = new HashMap<>();
      response.headers().forEach(entry -> respHeaders.put(entry.getKey(), entry.getValue()));
      return new Response(response.statusCode(), respHeaders, buffer.toString());
    })).onComplete(ar -> client.close());
  }
}
