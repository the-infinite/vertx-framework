package io.github.the_infinite.core.doc.impl;

import io.vertx.core.Future;
import io.github.the_infinite.core.doc.HttpClient;
import io.github.the_infinite.core.doc.HttpClientParameter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * An HTTP client used for load testing an endpoint.
 */
public class BurstHttpClient implements HttpClient {
    @Override
    public String getName() {
        return "Burst HTTP Client";
    }

    @Override
    public String getDescription() {
        return "Used for load testing an endpoint by sending bursts of requests across multiple parallel clients.";
    }

    @Override
    public String getUsageSnippet() {
        return """
            int parallelClients = {{parallelClientCount}};
            int requestsPerClient = {{requestsPerClient}};

            WebClient client = WebClient.create(vertx);

            for (int i = 0; i < parallelClients; i++) {
                vertx.executeBlocking(promise -> {
                    for (int j = 0; j < requestsPerClient; j++) {
                        client.get(port, host, path)
                              .send()
                              .onComplete(ar -> {
                                  // Track progress
                              });
                    }
                    promise.complete();
                });
            }
            """;
    }

    @Override
    public List<HttpClientParameter> getParameters() {
        return List.of(
            new HttpClientParameter("parallelClientCount", "Parallel Client Count", "number", "5"),
            new HttpClientParameter("requestsPerClient", "Requests per Client", "number", "100")
        );
    }

    @Override
    public Future<Response> execute(String method, String url, Map<String, String> headers, String body, Map<String, String> parameters) {
        int parallelClientCount = Integer.parseInt(parameters.getOrDefault("parallelClientCount", "5"));
        int requestsPerClient = Integer.parseInt(parameters.getOrDefault("requestsPerClient", "100"));

        final var classic = new ClassicHttpClient();
        final var futures = new ArrayList<Future<Response>>();

        for (int i = 0; i < parallelClientCount; i++) {
            for (int j = 0; j < requestsPerClient; j++) {
                futures.add(classic.execute(method, url, headers, body, parameters));
            }
        }

        return Future.join(futures).map(composite -> {
            int total = futures.size();
            long succeeded = futures.stream().filter(f -> f.succeeded() && f.result().statusCode() < 400).count();
            String summary = String.format("Burst complete. Total requests: %d, Succeeded (2xx/3xx): %d", total, succeeded);
            return new Response(200, new HashMap<>(), summary);
        });
    }
}
