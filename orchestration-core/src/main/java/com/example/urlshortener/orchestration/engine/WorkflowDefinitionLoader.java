package com.example.urlshortener.orchestration.engine;

import com.example.urlshortener.orchestration.model.WorkflowDefinition;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads a workflow definition from JSON and validates it as a DAG before it can be run.
 *
 * <p>Validating at load time rather than at dispatch time means a malformed graph fails loudly
 * when it is introduced, not silently in the middle of a run where an empty ready set looks
 * identical to a stalled scheduler.
 */
public final class WorkflowDefinitionLoader {

    private final ObjectMapper mapper;

    public WorkflowDefinitionLoader() {
        this(new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false));
    }

    public WorkflowDefinitionLoader(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public WorkflowDefinition fromClasspath(String resource) {
        try (InputStream in = WorkflowDefinitionLoader.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("No workflow definition on the classpath at " + resource);
            }
            return validated(mapper.readValue(in, WorkflowDefinition.class));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read workflow definition " + resource, e);
        }
    }

    public WorkflowDefinition fromFile(Path path) {
        try {
            return validated(mapper.readValue(Files.readAllBytes(path), WorkflowDefinition.class));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read workflow definition " + path, e);
        }
    }

    public WorkflowDefinition fromJson(String json) {
        try {
            return validated(mapper.readValue(json, WorkflowDefinition.class));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not parse workflow definition", e);
        }
    }

    private static WorkflowDefinition validated(WorkflowDefinition definition) {
        DagValidator.validate(definition);
        return definition;
    }
}
