package com.example.urlshortener.orchestration.engine;

import com.example.urlshortener.orchestration.model.NodeDefinition;
import com.example.urlshortener.orchestration.model.WorkflowDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DagValidatorTest {

    private static NodeDefinition node(String id, String... dependsOn) {
        return new NodeDefinition(id, "TestAgent", List.of(dependsOn), null, false, null,
                1000, 1, 100, null, null, null, false, false, null, List.of(), id);
    }

    private static WorkflowDefinition definition(NodeDefinition... nodes) {
        return new WorkflowDefinition("test", 1, "test definition", List.of(nodes));
    }

    @Test
    void a_valid_graph_returns_a_topological_order() {
        WorkflowDefinition definition = definition(
                node("a"), node("b", "a"), node("c", "a"), node("d", "b", "c"));

        List<String> order = DagValidator.validate(definition);

        assertThat(order).hasSize(4);
        assertThat(order.indexOf("a")).isLessThan(order.indexOf("b"));
        assertThat(order.indexOf("b")).isLessThan(order.indexOf("d"));
        assertThat(order.indexOf("c")).isLessThan(order.indexOf("d"));
    }

    @Test
    void a_cycle_is_rejected_and_names_the_nodes_involved() {
        WorkflowDefinition definition = definition(
                node("a", "c"), node("b", "a"), node("c", "b"));

        assertThatThrownBy(() -> DagValidator.validate(definition))
                .isInstanceOf(DagValidator.InvalidDefinitionException.class)
                .hasMessageContaining("cycle")
                .hasMessageContaining("a");
    }

    @Test
    void a_self_dependency_is_rejected() {
        assertThatThrownBy(() -> DagValidator.validate(definition(node("a", "a"))))
                .isInstanceOf(DagValidator.InvalidDefinitionException.class)
                .hasMessageContaining("depends on itself");
    }

    @Test
    void an_unknown_dependency_is_rejected() {
        assertThatThrownBy(() -> DagValidator.validate(definition(node("a"), node("b", "ghost"))))
                .isInstanceOf(DagValidator.InvalidDefinitionException.class)
                .hasMessageContaining("unknown node 'ghost'");
    }

    @Test
    void duplicate_node_ids_are_rejected() {
        assertThatThrownBy(() -> DagValidator.validate(definition(node("a"), node("a"))))
                .isInstanceOf(DagValidator.InvalidDefinitionException.class)
                .hasMessageContaining("Duplicate node id");
    }

    @Test
    void an_empty_definition_is_rejected() {
        assertThatThrownBy(() -> DagValidator.validate(definition()))
                .isInstanceOf(DagValidator.InvalidDefinitionException.class)
                .hasMessageContaining("no nodes");
    }

    @Test
    void a_node_without_an_agent_type_is_rejected() {
        NodeDefinition orphan = new NodeDefinition("a", "  ", List.of(), null, false, null,
                1000, 1, 100, null, null, null, false, false, null, List.of(), null);

        assertThatThrownBy(() -> DagValidator.validate(definition(orphan)))
                .isInstanceOf(DagValidator.InvalidDefinitionException.class)
                .hasMessageContaining("agentType");
    }

    @Test
    void the_shipped_sdlc_definition_is_a_valid_dag() {
        WorkflowDefinition sdlc = new WorkflowDefinitionLoader().fromClasspath("/workflows/sdlc.v1.json");

        List<String> order = DagValidator.validate(sdlc);

        assertThat(sdlc.name()).isEqualTo("sdlc");
        assertThat(order).startsWith("ingest").contains("policy-eval", "release-gate", "summary");
        assertThat(order.indexOf("policy-eval")).isLessThan(order.indexOf("release-gate"));
        assertThat(order.indexOf("release-gate")).isLessThan(order.indexOf("summary"));
    }
}
