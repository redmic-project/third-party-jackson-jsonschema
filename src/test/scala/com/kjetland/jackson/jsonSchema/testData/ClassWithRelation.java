package com.kjetland.jackson.jsonSchema.testData;

import com.kjetland.jackson.jsonSchema.annotations.JsonSchemaUrl;

/**
 * Created by noel on 5/12/16.
 */
public class ClassWithRelation {

    private Long id;

    private String name;

    @JsonSchemaUrl(value="ACCESSIBILITY")
    private Relation type;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Relation getType() {
        return type;
    }

    public void setType(Relation type) {
        this.type = type;
    }

    public Long getId() {

        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }
}
