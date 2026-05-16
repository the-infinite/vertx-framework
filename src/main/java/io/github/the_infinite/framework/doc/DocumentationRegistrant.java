package io.github.the_infinite.framework.doc;

import io.github.the_infinite.framework.doc.impl.BurstHttpClient;
import io.github.the_infinite.framework.doc.impl.ClassicHttpClient;
import io.github.the_infinite.framework.doc.impl.ThrottledHttpClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Singleton class to manage global documentation settings and collect route descriptions.
 * It stores global headers, authentication settings, and the rate limit.
 */
public class DocumentationRegistrant {
    private static final DocumentationRegistrant INSTANCE = new DocumentationRegistrant();

    private final Map<String, String> globalHeaders = new ConcurrentHashMap<>();
    private final Map<String, String> authSettings = new ConcurrentHashMap<>();
    private final List<RegisteredRoute> registeredRoutes = Collections.synchronizedList(new ArrayList<>());
    private final List<HttpClient> httpClients = Collections.synchronizedList(new ArrayList<>());
    private Integer globalRateLimit;

    private DocumentationRegistrant() {
        registerHttpClient(new ClassicHttpClient());
        registerHttpClient(new ThrottledHttpClient());
        registerHttpClient(new BurstHttpClient());
    }

    public static DocumentationRegistrant getInstance() {
        return INSTANCE;
    }

    public void setGlobalHeaders(Map<String, String> headers) {
        this.globalHeaders.clear();
        this.globalHeaders.putAll(headers);
    }

    public Map<String, String> getGlobalHeaders() {
        return Collections.unmodifiableMap(globalHeaders);
    }

    public void setAuthSettings(Map<String, String> settings) {
        this.authSettings.clear();
        this.authSettings.putAll(settings);
    }

    public Map<String, String> getAuthSettings() {
        return Collections.unmodifiableMap(authSettings);
    }

    public void setGlobalRateLimit(Integer rateLimit) {
        this.globalRateLimit = rateLimit;
    }

    public Integer getGlobalRateLimit() {
        return globalRateLimit;
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
