package io.github.the_infinite.framework.json;

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.vertx.core.json.jackson.DatabindCodec;
import io.vertx.core.spi.JsonFactory;
import io.vertx.core.spi.json.JsonCodec;

/// A Vert.x {@link JsonFactory} that customizes the shared Jackson mapper used by
/// {@code io.vertx.core.json.Json} so that Java 8 date/time types (e.g.
/// {@link java.time.OffsetDateTime}) can be (de)serialized. This is required because the
/// framework's request body binding ({@code RequestBody.asPojo}) and {@code Json.decodeValue}
/// use this mapper, and without the {@code jackson-datatype-jsr310} module those types fail to
/// decode with {@code MapperFeature.REQUIRE_HANDLERS_FOR_JAVA8_TIMES}.
public class FrameworkJsonFactory implements JsonFactory {
  static {
    DatabindCodec.mapper().registerModule(new JavaTimeModule());
  }

  @Override
  public int order() {
    return 0;
  }

  @Override
  public JsonCodec codec() {
    return new DatabindCodec();
  }
}
