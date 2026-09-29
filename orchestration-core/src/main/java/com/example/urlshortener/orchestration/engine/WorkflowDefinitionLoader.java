package com.example.urlshortener.orchestration.engine;

import com.example.urlshortener.orchestration.model.WorkflowDefinition;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Loads a workflow definition from JSON and checks it before the engine sees it. */
public final class WorkflowDefinitionLoader {

    private final ObjectMapper mapper;
    private final WorkflowDefinitionSchema schema;

    public WorkflowDefinitionLoader() {
        this(new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false));
    }

    public WorkflowDefinitionLoader(ObjectMapper mapper) {
        this.mapper = mapper;
        this.schema = new WorkflowDefinitionSchema();
    }

    public WorkflowDefinition fromClasspath(String resource) {
        try (InputStream in = WorkflowDefinitionLoader.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("No workflow definition on the classpath at " + resource);
            }
            return validated(mapper.readTree(in));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read workflow definition " + resource, e);
        }
    }

    public WorkflowDefinition fromFile(Path path) {
        try {
            return validated(mapper.readTree(Files.readAllBytes(path)));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read workflow definition " + path, e);
        }
    }

    public WorkflowDefinition fromJson(String json) {
        try {
            return validated(mapper.readTree(json));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not parse workflow definition", e);
        }
    }

    /** Schema first, then the graph; the definition is only converted once both pass. */
    private WorkflowDefinition validated(JsonNode raw) {
        schema.validate(raw);
        WorkflowDefinition definition = mapper.convertValue(raw, WorkflowDefinition.class);
        DagValidator.validate(definition);
        return definition;
    }
}
