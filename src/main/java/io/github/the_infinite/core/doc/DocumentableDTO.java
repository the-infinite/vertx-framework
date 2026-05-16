package io.github.the_infinite.core.doc;

/**
 * Interface for Data Transfer Objects that can be documented.
 * Provides an example representation of the DTO for documentation purposes.
 */
public interface DocumentableDTO {
    /**
     * Returns a map representing an example of this DTO.
     * This map will be used by the documentation framework to render example responses.
     *
     * @return a map of property names to example values.
     */
    String toExample();
}
