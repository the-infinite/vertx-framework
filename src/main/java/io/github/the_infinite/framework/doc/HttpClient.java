package io.github.the_infinite.framework.doc;

import io.vertx.core.Future;
import java.util.List;
import java.util.Map;

/**
 * Interface for pluggable HTTP clients used in the documentation framework.
 * Different implementations can provide various ways of sending network requests.
 */
public interface HttpClient {
    /**
     * Returns the human-readable name of this HTTP client.
     *
     * @return the client's name.
     */
    String getName();

    /**
     * Returns a description of what this HTTP client does or how it behaves.
     *
     * @return the client's description.
     */
    String getDescription();

    /**
     * Returns the snippet or configuration needed to use this client for a given request.
     * This could be used to generate code snippets in the documentation.
     *
     * @return a code snippet or usage instruction.
     */
    String getUsageSnippet();

    /**
     * Returns a list of configurable parameters for this HTTP client.
     * These parameters will be rendered in the documentation UI.
     *
     * @return the list of parameters.
     */
    default List<HttpClientParameter> getParameters() {
        return List.of();
    }

    /**
     * Performs an HTTP request using this client's specific implementation and behavior.
     *
     * @param method     HTTP method (GET, POST, etc.)
     * @param url        Full URL to send the request to.
     * @param headers    Map of headers to include.
     * @param body       Optional request body.
     * @param parameters Client-specific parameters provided from the UI.
     * @return a future resolving to the response.
     */
    Future<Response> execute(
        String method,
        String url,
        Map<String, String> headers,
        String body,
        Map<String, String> parameters
    );

    /**
     * Simple response container for documentation testing.
     */
    record Response(int statusCode, Map<String, String> headers, String body) {}
}
