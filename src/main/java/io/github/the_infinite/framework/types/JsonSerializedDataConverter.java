package io.github.the_infinite.framework.types;

import io.github.the_infinite.framework.utils.DataHelpers;

import java.util.Map;

import io.vertx.core.json.JsonObject;
import jakarta.persistence.AttributeConverter;

@SuppressWarnings("unused")
public class JsonSerializedDataConverter implements AttributeConverter<Map<String, Object>, String> {
    @Override
    public String convertToDatabaseColumn(Map<String, Object> tModel) {
        try {
            return DataHelpers.toBase64(new JsonObject(tModel).encode());
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public Map<String, Object> convertToEntityAttribute(String s) {
        try {
            return new JsonObject(DataHelpers.fromBase64(s)).getMap();
        } catch (Exception e) {
            return null;
        }
    }
}
