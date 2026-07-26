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

    /**
     * Enum to define different documentation rendering modes.
     * CONVENTIONAL: Standard documentation view with detailed endpoint information.
     * EXTERNAL: External-facing documentation view similar to Paystack API docs with group descriptions.
     */
    public enum DocumentationMode {
        CONVENTIONAL,
        EXTERNAL
    }

    private final Map<String, String> legacyGlobalHeaders = new ConcurrentHashMap<>();
    private final Map<String, String> unauthenticatedGlobalHeaders = new ConcurrentHashMap<>();
    private final Map<String, String> authenticatedGlobalHeaders = new ConcurrentHashMap<>();
    private final Map<String, String> authSettings = new ConcurrentHashMap<>();
    private final List<RegisteredRoute> registeredRoutes = Collections.synchronizedList(new ArrayList<>());
    private final List<HttpClient> httpClients = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, String> groupDescriptions = new ConcurrentHashMap<>();
    @Getter
    private Integer globalRateLimit;
    @Getter
    private DocumentationMode documentationMode = DocumentationMode.CONVENTIONAL;

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

    @SuppressWarnings("unused")
    public void setDocumentationMode(DocumentationMode mode) {
        this.documentationMode = mode;
    }

    @SuppressWarnings("unused")
    public void setGroupDescription(String groupName, String description) {
        if (groupName == null || groupName.isBlank()) {
            throw new IllegalArgumentException("Group name cannot be null or blank");
        }
        this.groupDescriptions.put(groupName.trim(), description != null ? description.trim() : "");
    }

    @SuppressWarnings("unused")
    public void setGroupDescriptions(Map<String, String> descriptions) {
        this.groupDescriptions.clear();
        if (descriptions != null) {
            descriptions.forEach((groupName, desc) -> {
                if (groupName != null && !groupName.isBlank()) {
                    this.groupDescriptions.put(groupName.trim(), desc != null ? desc.trim() : "");
                }
            });
        }
    }

    public String getGroupDescription(String groupName) {
        if (groupName == null || groupName.isBlank()) {
            return "N/A";
        }
        String description = groupDescriptions.get(groupName.trim());
        return description == null || description.isBlank() ? "N/A" : description;
    }

    public Map<String, String> getGroupDescriptions() {
        return Collections.unmodifiableMap(groupDescriptions);
    }

    /**
     * Inner record to store a registered route's path, method, and description.
     */
    public record RegisteredRoute(String path, String method, String controllerClass,
                                  RouteDescription description) {
    }
}
