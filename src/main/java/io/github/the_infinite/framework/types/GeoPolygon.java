package io.github.the_infinite.framework.types;

import io.github.the_infinite.framework.ConfigurationRegistrant;
import io.github.the_infinite.framework.utils.DataHelpers;

import io.vertx.core.Future;
import jakarta.persistence.AttributeConverter;

@SuppressWarnings("unused")
public final class GeoPolygon {
    private String type;
    private double[][][] coordinates;

    public GeoPolygon() {
        // Default constructor for deserialization
    }

    public GeoPolygon(String type, double[][][] coordinates) {
        this.type = type;
        this.coordinates = coordinates;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public double[][][] getCoordinates() {
        return coordinates;
    }

    public void setCoordinates(double[][][] coordinates) {
        this.coordinates = coordinates;
    }

    /**
     * Checks if the given latitude and longitude are inside the polygon. Assumes the first
     * element of coordinates is the exterior ring and any subsequent elements are holes.
     *
     * @param lat The latitude of the point to check.
     * @param lon The longitude of the point to check.
     * @return A future that resolves to true if the point is inside the polygon, false otherwise.
     */
    public Future<Boolean> contains(double lat, double lon) {
        return ConfigurationRegistrant.vertx().executeBlocking(() -> {
            if (coordinates == null || coordinates.length == 0) {
                return false;
            }

            // Check exterior ring
            if (!isPointInPolygon(lat, lon, coordinates[0])) {
                return false;
            }

            // Check holes
            for (int i = 1; i < coordinates.length; i++) {
                if (isPointInPolygon(lat, lon, coordinates[i])) {
                    return false;
                }
            }

            //? This is okay.
            return true;
        });
    }

    private boolean isPointInPolygon(double lat, double lon, double[][] ring) {
        boolean inside = false;
        for (int i = 0, j = ring.length - 1; i < ring.length; j = i++) {
            // GeoJSON coordinates are [longitude, latitude]
            double xi = ring[i][0], yi = ring[i][1];
            double xj = ring[j][0], yj = ring[j][1];

            boolean intersect = ((yi > lat) != (yj > lat))
                    && (lon < (xj - xi) * (lat - yi) / (yj - yi) + xi);
            if (intersect) {
                inside = !inside;
            }
        }
        return inside;
    }

    public static class Converter implements AttributeConverter<GeoPolygon, String> {
        @Override
        public String convertToDatabaseColumn(GeoPolygon model) {
            try {
                return DataHelpers.serializeObject(model);
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        public GeoPolygon convertToEntityAttribute(String s) {
            try {
                return DataHelpers.deserializeObject(s, GeoPolygon.class);
            } catch (Exception e) {
                return null;
            }
        }
    }
}
