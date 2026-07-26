package io.github.the_infinite.framework.doc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DocumentationJsonFormatterTest {

  @Test
  void prettyPrintsMinifiedJsonObjects() {
    String formatted = DocumentationJsonFormatter.formatJsonIfPossible("{\"name\":\"Ada\",\"roles\":[\"admin\",\"auditor\"],\"meta\":{\"active\":true}} ");

    assertEquals("""
      {
        "name" : "Ada",
        "roles" : [ "admin", "auditor" ],
        "meta" : {
          "active" : true
        }
      }""".stripIndent(), formatted);
  }

  @Test
  void prettyPrintsArraysToo() {
    String formatted = DocumentationJsonFormatter.formatJsonIfPossible("[{\"id\":1},{\"id\":2}]");

    assertEquals("""
      [ {
        "id" : 1
      }, {
        "id" : 2
      } ]""".stripIndent(), formatted);
  }

  @Test
  void leavesNonJsonTextUntouched() {
    String raw = "not-json-example";

    assertEquals(raw, DocumentationJsonFormatter.formatJsonIfPossible(raw));
  }
}
