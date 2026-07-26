package io.github.the_infinite.framework.doc;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Formats documentation examples and payload snippets for display.
 */
final class DocumentationJsonFormatter {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private DocumentationJsonFormatter() {
  }

  static String formatJsonIfPossible(String rawValue) {
    if (rawValue == null) {
      return "";
    }

    String trimmed = rawValue.trim();
    if (trimmed.isEmpty()) {
      return rawValue;
    }

    try {
      return MAPPER.writerWithDefaultPrettyPrinter()
        .writeValueAsString(MAPPER.readTree(trimmed));
    } catch (Exception ignored) {
      return rawValue;
    }
  }
}
