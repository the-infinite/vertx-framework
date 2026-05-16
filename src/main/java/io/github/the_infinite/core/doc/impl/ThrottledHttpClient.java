package io.github.the_infinite.core.doc.impl;

import io.github.the_infinite.core.ConfigurationRegistrant;
import io.github.the_infinite.core.doc.HttpClient;
import io.github.the_infinite.core.doc.HttpClientParameter;

import java.util.List;
import java.util.Map;

import io.vertx.core.Future;
import io.vertx.core.Promise;

/**
 * An HTTP client that simulates network throttling during the lifetime of a request.
 */
public class ThrottledHttpClient implements HttpClient {
  @Override
  public String getName() {
    return "Throttled HTTP Client";
  }

  @Override
  public String getDescription() {
    return "An HTTP client that simulates network throttling (latency and delay) during the lifetime of a request.";
  }

  @Override
  public String getUsageSnippet() {
    return """
      // Throttling configuration
      double probability = {{throttleProbability}};
      double latencyExponent = {{latencyExponent}};

      WebClient client = WebClient.create(vertx);

      // Simulating delay before request
      if (Math.random() < probability) {
          long delay = (long) Math.pow(Math.random() * 10, latencyExponent);
          vertx.setTimer(delay, id -> sendRequest(client));
      } else {
          sendRequest(client);
      }
      """;
  }

  @Override
  public List<HttpClientParameter> getParameters() {
    return List.of(
      new HttpClientParameter("throttleProbability", "Throttle Probability (0.0 - 1.0)", "number", "0.1"),
      new HttpClientParameter("latencyExponent", "Latency Exponent", "number", "2.0")
    );
  }

  @Override
  public Future<Response> execute(String method, String url, Map<String, String> headers, String body, Map<String, String> parameters) {
    final var probability = Double.parseDouble(parameters.getOrDefault("throttleProbability", "0.1"));
    final var latencyExponent = Double.parseDouble(parameters.getOrDefault("latencyExponent", "2.0"));

    final var vertx = ConfigurationRegistrant.vertx();
    final var promise = Promise.<Response>promise();
    final var client = new ClassicHttpClient();

    long delay = 0;
    if (Math.random() < probability) {
      delay = (long) Math.pow(Math.random() * 10, latencyExponent);
    }

    if (delay > 0) {
      vertx.setTimer(delay, id -> client.execute(method, url, headers, body, parameters).onComplete(promise));
    } else {
      client.execute(method, url, headers, body, parameters).onComplete(promise);
    }

    return promise.future();
  }
}
