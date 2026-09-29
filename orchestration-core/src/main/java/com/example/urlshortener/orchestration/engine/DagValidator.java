package com.example.urlshortener.orchestration.engine;

import com.example.urlshortener.orchestration.model.NodeDefinition;
import com.example.urlshortener.orchestration.model.WorkflowDefinition;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Checks a workflow definition is a valid DAG before anything runs. */
public final class DagValidator {

    /** Thrown when a definition is not a valid DAG. */
    public static class InvalidDefinitionException extends RuntimeException {

        public InvalidDefinitionException(String message) {
            super(message);
        }
    }

    private DagValidator() {
    }

    /**
     * @return node ids in a valid topological order
     * @throws InvalidDefinitionException on duplicate ids, unknown dependencies, or a cycle
     */
    public static List<String> validate(WorkflowDefinition definition) {
        if (definition.nodes().isEmpty()) {
            throw new InvalidDefinitionException("Workflow definition '" + definition.name() + "' has no nodes.");
        }

        Set<String> ids = new HashSet<>();
        for (NodeDefinition node : definition.nodes()) {
            if (node.id() == null || node.id().isBlank()) {
                throw new InvalidDefinitionException("A node is missing its id.");
            }
            if (!ids.add(node.id())) {
                throw new InvalidDefinitionException("Duplicate node id: " + node.id());
            }
            if (node.agentType() == null || node.agentType().isBlank()) {
                throw new InvalidDefinitionException("Node '" + node.id() + "' declares no agentType.");
            }
        }

        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, List<String>> outgoing = new HashMap<>();
        definition.nodes().forEach(n -> {
            inDegree.put(n.id(), 0);
            outgoing.put(n.id(), new ArrayList<>());
        });

        for (NodeDefinition node : definition.nodes()) {
            for (String dependency : node.dependsOn()) {
                if (!ids.contains(dependency)) {
                    throw new InvalidDefinitionException(
                            "Node '" + node.id() + "' depends on unknown node '" + dependency + "'.");
                }
                if (dependency.equals(node.id())) {
                    throw new InvalidDefinitionException("Node '" + node.id() + "' depends on itself.");
                }
                outgoing.get(dependency).add(node.id());
                inDegree.merge(node.id(), 1, Integer::sum);
            }
        }

        // Kahn's algorithm: repeatedly remove a node with no unmet dependencies.
        Deque<String> ready = new ArrayDeque<>();
        inDegree.forEach((id, degree) -> {
            if (degree == 0) {
                ready.add(id);
            }
        });

        List<String> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            String current = ready.poll();
            order.add(current);
            for (String next : outgoing.get(current)) {
                if (inDegree.merge(next, -1, Integer::sum) == 0) {
                    ready.add(next);
                }
            }
        }

        if (order.size() != definition.nodes().size()) {
            List<String> inCycle = inDegree.entrySet().stream()
                    .filter(e -> e.getValue() > 0)
                    .map(Map.Entry::getKey)
                    .sorted()
                    .toList();
            throw new InvalidDefinitionException("Workflow definition contains a cycle involving: " + inCycle);
        }

        return List.copyOf(order);
    }
}
