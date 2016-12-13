package com.kjetland.jackson.jsonSchema.testData;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Created by noel on 13/12/16.
 */
public enum GeoJsonObjectTypes {

    // @formatter:off
    FEATURE(Constants.FEATURE),
    FEATURE_COLLECTION(Constants.FEATURE_COLLECTION);

    // @formatter:on

    final String type;

    GeoJsonObjectTypes(String type) {
        this.type = type;
    }

    @JsonCreator
    public static GeoJsonObjectTypes fromString(String text) {
        if (text != null) {
            for (GeoJsonObjectTypes b : GeoJsonObjectTypes.values()) {
                if (text.equalsIgnoreCase(b.type)) {
                    return b;
                }
            }
        }
        throw new IllegalArgumentException(text + " has no corresponding value");
    }

    @JsonValue
    @Override
    public String toString() {
        return type;
    }

    public static class Constants {
        public static final String FEATURE = "Feature";
        public static final String FEATURE_COLLECTION = "FeatureCollection";
    }
}
