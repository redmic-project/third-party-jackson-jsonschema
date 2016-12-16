package com.kjetland.jackson.jsonSchema.testData;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.kjetland.jackson.jsonSchema.annotations.JsonSchemaFormat;
import com.kjetland.jackson.jsonSchema.annotations.JsonSchemaUrl;
import com.kjetland.jackson.jsonSchema.annotations.JsonSchemaIgnore;
import com.vividsolutions.jts.geom.LineString;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

import java.util.List;

/**
 * Created by noel on 5/12/16.
 */
public class ClassWithRelation {

    @NotNull
    private LineString geometry;

    private GeoJsonObjectTypes type;


    private Long id;

    @Size(min=0, max=2)
    private String name;

    @JsonSchemaUrl(value="ACCESSIBILITY")
    private Relation relationType;

    @JsonSchemaIgnore()
    private Relation parent;

    @JsonSchemaIgnore()
    private List<Relation> ancestors;

    @JsonSchemaIgnore()
    private String hide;

    public LineString getGeometry() {
        return geometry;
    }

    public void setGeometry(LineString geometry) {
        this.geometry = geometry;
    }

    public GeoJsonObjectTypes getType() {
        return type;
    }

    public void setType(GeoJsonObjectTypes geoJsonType) {
        this.type = geoJsonType;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Relation getRelationType() {
        return relationType;
    }

    public void setRelationType(Relation relationType) {
        this.relationType = relationType;
    }

    public void setParent(Relation parent) {
        this.parent = parent;
    }

    public Relation getParent() {
        return parent;
    }

    public List<Relation> getAncestors() {
        return ancestors;
    }

    public void setAncestors(List<Relation> ancestors) {
        this.ancestors = ancestors;
    }

    public String getHide() {
        return hide;
    }

    public void setHide(String hide) {
        this.hide = hide;
    }
}
