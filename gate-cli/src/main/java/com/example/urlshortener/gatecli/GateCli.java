package com.example.urlshortener.gatecli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Callable;

/**
 * A scriptable front end for the approval API (ADR-008).
 *
 * <p>The CLI exists so a decision can be made from a terminal or a pipeline without hand-writing
 * curl, but it is deliberately thin: it posts to the same endpoint a human would, and it cannot
 * do anything the API would refuse. In particular it has no way to approve without an actor and a
 * rationale, because the server requires both.
 *
 * <pre>
 *   java -jar gate-cli.jar list      --run run_123
 *   java -jar gate-cli.jar decide    --run run_123 --gate release-gate --decision READY \
 *                                    --actor madhu --rationale "All mandatory policies pass."
 *   java -jar gate-cli.jar resume    --run run_123 --actor madhu --reason "Rollback confirmed."
 * </pre>
 */
@Command(name = "gate-cli", mixinStandardHelpOptions = true, version = "1.0.0",
        description = "Inspect and decide human approval gates on a workflow run.",
        subcommands = {GateCli.ListGates.class, GateCli.Decide.class, GateCli.Resume.class, GateCli.Status.class})
public class GateCli {

    /** Shared connection options. */
    public static class Connection {

        @Option(names = {"-u", "--base-url"}, defaultValue = "${env:GATE_CLI_BASE_URL:-http://localhost:8080}",
                description = "Base URL of the running instance (default: ${DEFAULT-VALUE}).")
        String baseUrl;

        @Option(names = {"-k", "--api-key"}, defaultValue = "${env:GATE_CLI_API_KEY:-demo-operator-key}",
                description = "API key with the CONTROL scope. Prefer the GATE_CLI_API_KEY environment "
                        + "variable: an argument is visible in the process list and the shell history.")
        String apiKey;

        @Option(names = {"-r", "--run"}, required = true, description = "Workflow run id.")
        String runId;
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public static void main(String[] args) {
        System.exit(new CommandLine(new GateCli()).execute(args));
    }

    @Command(name = "list", description = "List the gates on a run and their current state.")
    static class ListGates implements Callable<Integer> {

        @CommandLine.Mixin
        Connection connection;

        @Override
        public Integer call() throws Exception {
            JsonNode gates = get(connection, "/api/v1/workflows/" + connection.runId + "/gates");
            if (gates == null) {
                return 1;
            }
            System.out.printf("%-20s %-20s %s%n", "GATE", "STATE", "DECISION");
            for (JsonNode gate : gates) {
                System.out.printf("%-20s %-20s %s%n",
                        gate.path("gateId").asText(),
                        gate.path("state").asText(),
                        gate.path("decision").isNull() ? "-" : gate.path("decision").asText("-"));
            }
            return 0;
        }
    }

    @Command(name = "status", description = "Show the state of every node in a run.")
    static class Status implements Callable<Integer> {

        @CommandLine.Mixin
        Connection connection;

        @Override
        public Integer call() throws Exception {
            JsonNode run = get(connection, "/api/v1/workflows/" + connection.runId);
            if (run == null) {
                return 1;
            }
            System.out.println("run             " + run.path("runId").asText());
            System.out.println("state           " + run.path("state").asText());
            System.out.println("definition v    " + run.path("definitionVersion").asLong());
            System.out.println("policy version  " + run.path("policyVersion").asText());
            System.out.println("release blocked " + run.path("releaseBlocked").asBoolean());
            System.out.println();
            System.out.printf("%-18s %-18s %s%n", "NODE", "STATE", "ATTEMPTS");
            for (JsonNode node : run.path("nodes")) {
                System.out.printf("%-18s %-18s %d%n", node.path("id").asText(),
                        node.path("state").asText(), node.path("attempts").asInt());
            }
            return 0;
        }
    }

    @Command(name = "decide", description = "Record a human decision on a gate.")
    static class Decide implements Callable<Integer> {

        @CommandLine.Mixin
        Connection connection;

        @Option(names = {"-g", "--gate"}, required = true, description = "Gate id, e.g. release-gate.")
        String gate;

        @Option(names = {"-d", "--decision"}, required = true,
                description = "APPROVE, READY, READY_WITH_ACCEPTED_LIMITATIONS, REJECT, NOT_READY or SAFE_STOP.")
        String decision;

        @Option(names = {"-a", "--actor"}, required = true, description = "Who is deciding. Recorded verbatim.")
        String actor;

        @Option(names = {"-m", "--rationale"}, required = true,
                description = "Why. Recorded verbatim and required: an approval with no stated reason is "
                        + "indistinguishable from a rubber stamp when the run is reviewed later.")
        String rationale;

        @Option(names = {"-c", "--conditions"}, description = "Any conditions attached to the decision.")
        String conditions;

        @Option(names = {"-w", "--wait-ms"}, defaultValue = "60000",
                description = "How long to wait for the run to settle afterwards.")
        long waitMs;

        @Override
        public Integer call() throws Exception {
            String body = JSON.createObjectNode()
                    .put("decision", decision)
                    .put("actor", actor)
                    .put("rationale", rationale)
                    .put("conditions", conditions)
                    .toString();

            JsonNode response = post(connection,
                    "/api/v1/workflows/" + connection.runId + "/gates/" + gate + "/decision?waitMs=" + waitMs, body);
            if (response == null) {
                return 1;
            }
            System.out.println("Recorded " + response.path("decision").asText()
                    + " on " + response.path("gateId").asText()
                    + " as " + response.path("decisionId").asText());
            return 0;
        }
    }

    @Command(name = "resume", description = "Resume a suspended or safe-stopped run.")
    static class Resume implements Callable<Integer> {

        @CommandLine.Mixin
        Connection connection;

        @Option(names = {"-a", "--actor"}, required = true, description = "Who is resuming.")
        String actor;

        @Option(names = {"-m", "--reason"}, required = true, description = "Why the run is being resumed.")
        String reason;

        @Option(names = {"-w", "--wait-ms"}, defaultValue = "60000", description = "How long to wait afterwards.")
        long waitMs;

        @Override
        public Integer call() throws Exception {
            String body = JSON.createObjectNode().put("actor", actor).put("reason", reason).toString();

            JsonNode response = post(connection,
                    "/api/v1/workflows/" + connection.runId + "/resume?waitMs=" + waitMs, body);
            if (response == null) {
                return 1;
            }
            System.out.println("Run " + response.path("runId").asText()
                    + " is now " + response.path("state").asText());
            return 0;
        }
    }

    // ---------------------------------------------------------------- transport

    private static JsonNode get(Connection connection, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(connection.baseUrl + path))
                .header("X-Api-Key", connection.apiKey)
                .timeout(Duration.ofMinutes(3))
                .GET()
                .build();
        return send(request);
    }

    private static JsonNode post(Connection connection, String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(connection.baseUrl + path))
                .header("X-Api-Key", connection.apiKey)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMinutes(3))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return send(request);
    }

    /** Prints the server's problem detail rather than a stack trace when a call is refused. */
    private static JsonNode send(HttpRequest request) throws Exception {
        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            System.err.println("Request failed with HTTP " + response.statusCode() + ":");
            System.err.println("  " + response.body());
            return null;
        }
        return JSON.readTree(response.body());
    }
}
