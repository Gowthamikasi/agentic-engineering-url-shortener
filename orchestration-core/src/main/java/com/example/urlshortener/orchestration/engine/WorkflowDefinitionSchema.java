package com.example.urlshortener.orchestration.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import java.io.InputStream;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Validates a workflow definition against its published JSON schema. */
public final class WorkflowDefinitionSchema {

    public static final String SCHEMA_RESOURCE = "/schemas/workflow-definition.schema.json";

    /** Thrown when a definition does not match its published schema. */
    public static class SchemaViolationException extends RuntimeException {

        private final List<String> violations;

        public SchemaViolationException(List<String> violations) {
            super("Workflow definition does not match " + SCHEMA_RESOURCE + ": " + String.join("; ", violations));
            this.violations = List.copyOf(violations);
        }

        public List<String> violations() {
            return violations;
        }
    }

    private final JsonSchema schema;

    public WorkflowDefinitionSchema() {
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        try (InputStream in = WorkflowDefinitionSchema.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("The workflow definition schema is missing from the classpath at "
                        + SCHEMA_RESOURCE);
            }
            this.schema = factory.getSchema(in);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Could not read " + SCHEMA_RESOURCE, e);
        }
    }

    /**
     * Reports every violation at once, because fixing them one at a time is tedious.
     *
     * @throws SchemaViolationException if the definition does not match the schema
     */
    public void validate(JsonNode definition) {
        Set<ValidationMessage> messages = schema.validate(definition);
        if (messages.isEmpty()) {
            return;
        }
        // Sorted and complete: reporting one violation at a time turns fixing a definition into a
        // guessing game.
        Set<String> sorted = new TreeSet<>();
        messages.forEach(m -> sorted.add(m.getMessage()));
        throw new SchemaViolationException(List.copyOf(sorted));
    }
}
