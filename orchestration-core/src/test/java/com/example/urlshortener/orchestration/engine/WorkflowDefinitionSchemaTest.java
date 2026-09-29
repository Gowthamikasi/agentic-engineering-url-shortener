package com.example.urlshortener.orchestration.engine;

import com.example.urlshortener.orchestration.model.WorkflowDefinition;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The published schema, tested as a gate rather than as documentation.
 *
 * <p>A schema nothing enforces drifts away from the code it claims to describe. These tests exist
 * so the schema and the loader cannot disagree: whatever the schema rejects, the engine refuses.
 */
class WorkflowDefinitionSchemaTest {

    private final WorkflowDefinitionLoader loader = new WorkflowDefinitionLoader();

    private static String definitionWith(String nodeBody) {
        return """
                { "name": "test", "version": 1, "nodes": [ %s ] }""".formatted(nodeBody);
    }

    @Test
    void the_shipped_definition_satisfies_its_own_published_schema() {
        assertThatCode(() -> loader.fromClasspath("/workflows/sdlc.v1.json")).doesNotThrowAnyException();
    }

    @Test
    void a_minimal_valid_definition_loads() {
        WorkflowDefinition definition = loader.fromJson(
                definitionWith("""
                        { "id": "ingest", "agentType": "RequirementIngestAgent" }"""));

        assertThat(definition.nodes()).hasSize(1);
        assertThat(definition.requireNode("ingest").maxAttempts()).isEqualTo(1);
    }

    /**
     * The check that earns the schema its place: a typo in a field name would otherwise be silently
     * ignored by Jackson, and the node would run with the default the author thought they had
     * overridden.
     */
    @Test
    void an_unknown_field_is_rejected_rather_than_silently_ignored() {
        assertThatThrownBy(() -> loader.fromJson(definitionWith("""
                { "id": "ingest", "agentType": "A", "maxAttemps": 3 }""")))
                .isInstanceOf(WorkflowDefinitionSchema.SchemaViolationException.class)
                .hasMessageContaining("maxAttemps");
    }

    @Test
    void a_misspelled_enum_value_is_rejected() {
        assertThatThrownBy(() -> loader.fromJson(definitionWith("""
                { "id": "ingest", "agentType": "A", "recoveryMode": "ROLLBACKABLE_MAYBE" }""")))
                .isInstanceOf(WorkflowDefinitionSchema.SchemaViolationException.class);

        assertThatThrownBy(() -> loader.fromJson(definitionWith("""
                { "id": "ingest", "agentType": "A", "joinType": "EITHER" }""")))
                .isInstanceOf(WorkflowDefinitionSchema.SchemaViolationException.class);
    }

    /** A zero retry budget is a definition that can never run a node; it should not load. */
    @Test
    void a_nonsensical_retry_budget_or_timeout_is_rejected() {
        assertThatThrownBy(() -> loader.fromJson(definitionWith("""
                { "id": "ingest", "agentType": "A", "maxAttempts": 0 }""")))
                .isInstanceOf(WorkflowDefinitionSchema.SchemaViolationException.class);

        assertThatThrownBy(() -> loader.fromJson(definitionWith("""
                { "id": "ingest", "agentType": "A", "timeoutMs": 0 }""")))
                .isInstanceOf(WorkflowDefinitionSchema.SchemaViolationException.class);
    }

    @Test
    void a_node_id_that_is_not_a_stable_slug_is_rejected() {
        assertThatThrownBy(() -> loader.fromJson(definitionWith("""
                { "id": "Ingest Node", "agentType": "A" }""")))
                .isInstanceOf(WorkflowDefinitionSchema.SchemaViolationException.class);
    }

    @Test
    void a_definition_missing_its_name_or_nodes_is_rejected() {
        assertThatThrownBy(() -> loader.fromJson("""
                { "version": 1, "nodes": [ { "id": "a", "agentType": "A" } ] }"""))
                .isInstanceOf(WorkflowDefinitionSchema.SchemaViolationException.class);

        assertThatThrownBy(() -> loader.fromJson("""
                { "name": "test", "version": 1, "nodes": [] }"""))
                .isInstanceOf(WorkflowDefinitionSchema.SchemaViolationException.class);
    }

    @Test
    void every_violation_is_reported_at_once_rather_than_one_at_a_time() {
        assertThatThrownBy(() -> loader.fromJson(definitionWith("""
                { "id": "BAD ID", "agentType": "A", "maxAttempts": 0, "joinType": "EITHER" }""")))
                .isInstanceOfSatisfying(WorkflowDefinitionSchema.SchemaViolationException.class,
                        e -> assertThat(e.violations()).hasSizeGreaterThanOrEqualTo(3));
    }

    /**
     * Schema and graph checks catch different things, and both must run: this definition is
     * perfectly well-formed and still cannot execute.
     */
    @Test
    void a_schema_valid_definition_can_still_be_rejected_as_a_graph() {
        assertThatThrownBy(() -> loader.fromJson("""
                { "name": "test", "version": 1, "nodes": [
                    { "id": "a", "agentType": "A", "dependsOn": ["b"] },
                    { "id": "b", "agentType": "A", "dependsOn": ["a"] } ] }"""))
                .isInstanceOf(DagValidator.InvalidDefinitionException.class)
                .hasMessageContaining("cycle");
    }

    @Test
    void an_exit_gate_must_name_non_empty_artifact_types() {
        assertThatThrownBy(() -> loader.fromJson(definitionWith("""
                { "id": "ingest", "agentType": "A", "producesArtifacts": [""] }""")))
                .isInstanceOf(WorkflowDefinitionSchema.SchemaViolationException.class);

        assertThatCode(() -> loader.fromJson(definitionWith("""
                { "id": "ingest", "agentType": "A", "producesArtifacts": ["RawRequirement"] }""")))
                .doesNotThrowAnyException();
    }
}
