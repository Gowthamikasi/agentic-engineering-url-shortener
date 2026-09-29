package com.example.urlshortener.orchestration.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A versioned, inspectable workflow graph.
 *
 * <p>The definition is data rather than code so a reviewer can read the governed path without
 * reading the engine, and so replanning can produce version N+1 as a diffable artifact rather
 * than as a hidden change in behaviour.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkflowDefinition(String name, long version, String description, List<NodeDefinition> nodes) {

    public WorkflowDefinition {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
    }

    public Optional<NodeDefinition> node(String id) {
        return nodes.stream().filter(n -> n.id().equals(id)).findFirst();
    }

    public NodeDefinition requireNode(String id) {
        return node(id).orElseThrow(() -> new IllegalArgumentException("No such node: " + id));
    }

    public Map<String, NodeDefinition> byId() {
        Map<String, NodeDefinition> map = new LinkedHashMap<>();
        nodes.forEach(n -> map.put(n.id(), n));
        return map;
    }

    /** Node ids that declare {@code dependsOn} containing the given id. */
    public List<String> consumersOf(String nodeId) {
        return nodes.stream().filter(n -> n.dependsOn().contains(nodeId)).map(NodeDefinition::id).toList();
    }

    public WorkflowDefinition withVersion(long newVersion) {
        return new WorkflowDefinition(name, newVersion, description, nodes);
    }
}
