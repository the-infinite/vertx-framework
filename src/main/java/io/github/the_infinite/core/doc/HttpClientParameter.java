package io.github.the_infinite.core.doc;

/**
 * Represents a configurable parameter for an HTTP client in the documentation UI.
 */
public record HttpClientParameter(String name, String label, String type, String defaultValue) {
}
