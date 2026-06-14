package io.github.the_infinite.framework.doc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.the_infinite.framework.doc.impl.BurstHttpClient;
import io.github.the_infinite.framework.doc.impl.ClassicHttpClient;
import io.github.the_infinite.framework.doc.impl.ThrottledHttpClient;
import lombok.Getter;

/**
 * Singleton class to manage global documentation settings and collect route descriptions.
 * It stores global headers, authentication settings, and the rate limit.
 */
@SuppressWarnings("unused")
public class DocumentationRegistrant {
    private static final DocumentationRegistrant INSTANCE = new DocumentationRegistrant();

    private final Map<String, String> legacyGlobalHeaders = new ConcurrentHashMap<>();
    private final Map<String, String> unauthenticatedGlobalHeaders = new ConcurrentHashMap<>();
    private final Map<String, String> authenticatedGlobalHeaders = new ConcurrentHashMap<>();
    private final Map<String, String> authSettings = new ConcurrentHashMap<>();
    private final List<RegisteredRoute> registeredRoutes = Collections.synchronizedList(new ArrayList<>());
    private final List<HttpClient> httpClients = Collections.synchronizedList(new ArrayList<>());
    @Getter
    private Integer globalRateLimit;

    private DocumentationRegistrant() {
        registerHttpClient(new ClassicHttpClient());
        registerHttpClient(new ThrottledHttpClient());
        registerHttpClient(new BurstHttpClient());
    }

    public static DocumentationRegistrant getInstance() {
        return INSTANCE;
    }

    @SuppressWarnings("unused")
    public void setGlobalHeaders(Map<String, String> headers) {
        this.legacyGlobalHeaders.clear();
        this.legacyGlobalHeaders.putAll(headers);
    }

    public Map<String, String> getGlobalHeaders() {
        final var combined = new java.util.LinkedHashMap<>(legacyGlobalHeaders);
        combined.putAll(unauthenticatedGlobalHeaders);
        combined.putAll(authenticatedGlobalHeaders);
        return Collections.unmodifiableMap(combined);
    }

    @SuppressWarnings("unused")
    public void setGlobalHeaders(Map<String, String> headers, boolean authenticatedOnly) {
        final var target = authenticatedOnly ? authenticatedGlobalHeaders : unauthenticatedGlobalHeaders;
        target.clear();
        target.putAll(headers);
    }

    public Map<String, String> getAuthenticatedGlobalHeaders() {
        final var combined = new java.util.LinkedHashMap<>(legacyGlobalHeaders);
        combined.putAll(authenticatedGlobalHeaders);
        return Collections.unmodifiableMap(combined);
    }

    public Map<String, String> getUnauthenticatedGlobalHeaders() {
        final var combined = new java.util.LinkedHashMap<>(legacyGlobalHeaders);
        combined.putAll(unauthenticatedGlobalHeaders);
        return Collections.unmodifiableMap(combined);
    }

    @SuppressWarnings("unused")
    public void setAuthSettings(Map<String, String> settings) {
        this.authSettings.clear();
        this.authSettings.putAll(settings);
    }

    public Map<String, String> getAuthSettings() {
        return Collections.unmodifiableMap(authSettings);
    }

    @SuppressWarnings("unused")
    public void setGlobalRateLimit(Integer rateLimit) {
        this.globalRateLimit = rateLimit;
    }

  public void registerHttpClient(HttpClient client) {
        httpClients.add(client);
    }

    public List<HttpClient> getHttpClients() {
        return Collections.unmodifiableList(httpClients);
    }

    public void registerRoute(String path, String method, String controllerClass, RouteDescription description) {
        registeredRoutes.add(new RegisteredRoute(path, method, controllerClass, description));
    }

    public List<RegisteredRoute> getRegisteredRoutes() {
        return Collections.unmodifiableList(registeredRoutes);
    }

    /**
     * Inner record to store a registered route's path, method, and description.
     */
    public record RegisteredRoute(String path, String method, String controllerClass,
                                  RouteDescription description) {
    }
}
