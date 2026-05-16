package io.github.the_infinite.framework.doc.impl;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import io.github.the_infinite.framework.ConfigurationRegistrant;
import io.github.the_infinite.framework.doc.HttpClient;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;

/**
 * A simple HTTP client leveraging Vert.x HTTP client implementation for scalability.
 */
public class ClassicHttpClient implements HttpClient {
  private final Supplier<Vertx> vertxSupplier;

  public ClassicHttpClient() {
    this(ConfigurationRegistrant::vertx);
  }

  public ClassicHttpClient(Vertx vertx) {
    this(() -> vertx);
  }

  private ClassicHttpClient(Supplier<Vertx> vertxSupplier) {
    this.vertxSupplier = vertxSupplier;
  }

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
                logger.info("Received response with status code {}", response.statusCode());
            })
            .onFailure(err -> {
                logger.error("Something went wrong", err);
            });
      """;
  }

  private static String appendQueryParameters(String url, Map<String, String> parameters) {
    if (parameters == null || parameters.isEmpty()) {
      return url;
    }

    try {
      final var uri = URI.create(url);
      final var existingQuery = uri.getRawQuery();
      final var query = new StringBuilder(existingQuery == null ? "" : existingQuery);

      for (final var entry : parameters.entrySet()) {
        if (entry.getKey() == null || entry.getValue() == null) {
          continue;
        }

        if (!query.isEmpty()) {
          query.append('&');
        }

        query.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
        query.append('=');
        query.append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
      }

      return new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(), query.toString(), uri.getFragment()).toString();
    } catch (IllegalArgumentException | URISyntaxException e) {
      throw new IllegalArgumentException("Invalid request URL: " + url, e);
    }
  }

  @Override
  public Future<Response> execute(String method, String url, Map<String, String> headers, String body, Map<String, String> parameters) {
    final var client = vertxSupplier.get().createHttpClient();
    final var httpMethod = HttpMethod.valueOf(method.toUpperCase());
    final var requestUrl = appendQueryParameters(url, parameters);

    final var vertxHeaders = MultiMap.caseInsensitiveMultiMap();
    if (headers != null) {
      headers.forEach(vertxHeaders::add);
    }

    return client.request(new RequestOptions()
      .setMethod(httpMethod)
      .setAbsoluteURI(requestUrl)
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
