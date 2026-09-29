# Reviewer guide

Every claim this repository makes, with the command that proves it and what you should see.

Two terminals: one running the application, one running the commands.

```bash
./mvnw verify                                     # terminal 1: build and test
java -jar app/target/agentic-url-shortener.jar    # terminal 1: run
export K='X-Api-Key: demo-operator-key'           # terminal 2
```

If you only have five minutes, run items 1, 12 and 17.

---

## Build and structure

| # | Claim | Command | Expected |
|---|---|---|---|
| 1 | Clean clone builds and all tests pass | `./mvnw verify` | `BUILD SUCCESS`, 181 unit/contract tests plus 16 integration tests, 0 failures |
| 2 | The two planes do not depend on each other | `./mvnw -pl app test -Dtest=ArchitectureBoundaryTest` | 7 rules pass. Break it: add an `orchestration` import to `url-shortener-api` and watch the build fail |
| 3 | The domain has no framework coupling | `grep -rl "org.springframework\|jakarta.persistence" url-shortener-domain/src/main` | no output |
| 4 | The workflow is data, not code | `cat orchestration-core/src/main/resources/workflows/sdlc.v1.json` | 18 nodes with dependencies, gates, timeouts, retry budgets and recovery modes |
| 5 | The policy set is versioned and readable | `cat policies/policy-set.v1.0.0.json` | 13 rules across 5 domains, each with `mandatory` |
| 5a | The workflow schema is enforced, not decorative | `./mvnw -pl orchestration-core test -Dtest=WorkflowDefinitionSchemaTest` | 10 tests. Add `"maxAttemps": 3` to a node and the definition is refused at load |
| 5b | Every executing node declares an exit gate | `grep -c producesArtifacts orchestration-core/src/main/resources/workflows/sdlc.v1.json` | 15 nodes declare what they owe the run |

## Application plane

| # | Claim | Command | Expected |
|---|---|---|---|
| 6 | Links are created and followed | `curl -X POST localhost:8080/api/v1/links -H "$K" -H 'Content-Type: application/json' -d '{"url":"https://example.org/docs"}'` then `curl -i localhost:8080/<code>` | `201` with a 7-character code; then `302` to the target |
| 7 | An expired link is `410`, not `404` | create with `"expiresAt"` a few seconds out, wait, then follow it | `410` with `errorCode: LINK_EXPIRED` — distinguishable from "never existed" |
| 8 | Private and internal hosts are refused | `curl -X POST .../links -H "$K" -d '{"url":"https://localhost/x"}'` | `422`, `URL_HOST_BLOCKED` — and no short code is created, so there is nothing to follow later |
| 9 | Dangerous schemes are refused | same with `javascript:alert(1)` | `400`, `URL_SCHEME_NOT_ALLOWED` |
| 10 | Scopes are enforced | repeat item 6 with `demo-reviewer-key` | `401` — a read key cannot create or approve |
| 11 | Idempotency is honest | POST twice with the same `Idempotency-Key` and body, then once with a different body | `201`, then `200` with the same code, then `409` |

## Control plane — the part that matters

| # | Claim | Command | Expected |
|---|---|---|---|
| 12 | **All three scenarios run end to end** | `./scripts/demo-scenarios.sh` | three runs, each `COMPLETED`, with bundles under `docs/scenarios/` |
| 13 | A clear requirement is **not** stopped by a gate | `grep -o '"state":"SKIPPED"' -c docs/scenarios/greenfield/run-*.json` and check `clarify` in the graph | `clarify` is `SKIPPED` with the reason recorded. REQ-D-009: no artificial gate |
| 14 | A vague requirement **does** stop the run | start Scenario C and read `/gates` before deciding | state `AWAITING_CLARIFICATION`; `implement` has 0 attempts. It does not guess what "a while" means |
| 15 | Parallel execution is real | `grep -E '"(NodeStarted\|NodeSucceeded)"' docs/scenarios/greenfield/history.json` around the verification nodes | `unit-tests`, `contract-tests`, `security-scan` and `docs` have overlapping start/end times |
| 16 | The join is real | same file, `policy-eval` | its `NodeStarted` is strictly after the last sibling's `NodeSucceeded` |
| 17 | **A gate is never approved by a timer** | `./mvnw -pl orchestration-core test -Dtest=WorkflowEngineTest` | `an_elapsed_approval_window_safe_stops_and_never_approves` passes. There is no code path from elapsed time to approval |
| 18 | Retry is bounded and engine-owned | greenfield bundle, `contract-tests` | `attempts: 2` — one injected transient failure, one retry, then success. Agents cannot retry internally |
| 19 | Rollback and resume work | brownfield `history.json` | `NodeFailed → RollbackStarted → RollbackCompleted → SafeStop → WorkflowResumed → NodeSucceeded` |
| 20 | Replanning preserves governance | ambiguous `run-*.json` | `definitionVersion: 2`; `Invalidated` for `quality-check` and `clarify`; a `Replanned` row with the version diff |
| 20a | **An exit gate catches a stage that produces nothing** | `./mvnw -pl orchestration-core test -Dtest=WorkflowEngineTest` | `a_node_that_reports_success_without_its_declared_artifact_fails_the_exit_gate`: the claim of success is refused because the declared artifact is absent |
| 20b | **Brownfield impact is read from the codebase** | brownfield `artifacts.json`, `ImpactAnalysis` | names real files with scores and the terms that matched. `filesScanned` is the size of the actual scan |
| 20c | Integration tests exercise both planes over HTTP | `./mvnw -pl app verify` | 16 tests on a real port: redirects, expiry, auth, a whole governed run, replanning, audit verification |
| 21 | Lineage reaches the requirement | `curl -H "$K" localhost:8080/api/v1/workflows/<runId>/lineage/summary:RunSummary:v1` | a chain back to `RawRequirement`, with the decisions in force at each step |

## Governance and evidence

| # | Claim | Command | Expected |
|---|---|---|---|
| 22 | The audit chain verifies | `curl -i -H "$K" localhost:8080/api/v1/workflows/<runId>/audit` | header `X-Audit-Chain: intact`. Edit a row in the database and it reports the row where it breaks |
| 23 | A mandatory policy failure blocks release | `./mvnw -pl policy-core test` | `a_mandatory_failure_blocks_release_and_names_the_rule`; and an expired exception is re-evaluated as FAIL, not waived |
| 24 | A gap is not reported as a pass | greenfield `artifacts.json`, `PolicyEvaluation` | `LIC-001: EXCEPTION_REQUESTED` — no licence report was produced, and it says so rather than passing |
| 25 | **MTTR excludes unrecovered failures** | `curl -H "$K" localhost:8080/api/v1/metrics/reliability` | `mttrMs` over recovered failures only; `unrecoveredFailureEvents` listed separately; every response `"dataClass": "DEMONSTRATION"` |

---

## Try to break it

The claims above are more convincing if you attack them:

- **Approve a gate that is not awaiting one** → `409` with the current state named.
- **Approve with a blank rationale** → `400`; the engine refuses an unexplained decision.
- **Add a cycle to `sdlc.v1.json`** → the run is refused at load, not discovered mid-flight.
- **Point a node at an unregistered agent type** → the node fails permanently rather than hanging.
- **Delete an audit row from the H2 file** → `X-Audit-Chain` reports the row where the chain breaks.
- **Add `import com.example.urlshortener.orchestration...` to a `url-shortener-api` class** → the
  module will not even compile, and if it did, `ArchitectureBoundaryTest` would fail.

## Where the seams are

Stated plainly so you do not have to find them yourself:

- Agents are **deterministic**, not LLM-backed (ASM-001). `StageAgent` is the seam where an LLM
  adapter would go; it has not been half-built.
- `implement` records a change set; it **does not write code** (EXC-004).
- The `TestRunnerAgent` reads the build's **real** Surefire and Failsafe XML. If no report matches,
  the node **fails** — absence of evidence is never reported as a pass.
- Reliability numbers come from three scripted runs and are labelled `DEMONSTRATION` in every response.
- The recorded approver is asserted by the API key holder, not authenticated (ADR-015).
