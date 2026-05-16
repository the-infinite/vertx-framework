package io.github.the_infinite.framework.env;

import io.vertx.config.ConfigRetriever;
import io.vertx.config.ConfigRetrieverOptions;
import io.vertx.config.ConfigStoreOptions;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

@SuppressWarnings("unused")
public class ApplicationProperties {
  Future<JsonObject> loadProperties(Vertx vertex) {
    var store = new ConfigStoreOptions()
      .setType("file")
      .setFormat("properties")
      .setConfig(new JsonObject().put("path", "application.properties")); // Path relative to the classpath or file system
    var options = new ConfigRetrieverOptions().addStore(store);
    var retriever = ConfigRetriever.create(vertex, options);
    return retriever.getConfig();
  }
}
