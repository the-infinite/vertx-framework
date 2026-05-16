package io.github.the_infinite.core.types;


import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKBReader;
import org.locationtech.jts.io.WKBWriter;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class GeometryConverter implements AttributeConverter<Geometry, String> {
  private static final WKBWriter writer = new WKBWriter();
  private static final WKBReader reader = new WKBReader();

  @Override
  public String convertToDatabaseColumn(Geometry geom) {
    return WKBWriter.toHex(writer.write(geom));
  }

  @Override
  public Geometry convertToEntityAttribute(String wkbString) {
    Geometry geom;
    try {
      geom = reader.read(WKBReader.hexToBytes(wkbString));
      return geom;
    } catch (ParseException e) {
      return null;
    }
  }
}
