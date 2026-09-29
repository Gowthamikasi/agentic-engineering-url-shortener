# Traceability matrix

Requirement → design → code → test → evidence. No orphan requirements, and no orphan code.

Read it in either direction: pick a requirement and find the test that proves it, or pick a class
and find the requirement it exists for.

## Confirmed requirements (from the assignment)

| ID | Requirement | Design | Code | Test | Evidence |
|---|---|---|---|---|---|
| REQ-C-001 | Working, runnable end-to-end prototype | ADR-014 | `app/Application` | full build | `./mvnw verify`, 205 tests |
| REQ-C-002 | URL shortener with core APIs, analytics, reliability | arch §3 | `url-shortener-*` | `LinksApiContractTest` (24) | reviewer guide 6-11 |
| REQ-C-003 | Interpret intent, identify ambiguity, normalise | arch §4 | `RequirementAgents` | `RequirementQualityAgentTest` (8) | `scenarios/ambiguous/` |
| REQ-C-004 | Task decomposition with dependencies | arch §4.1 | `DesignAgents.DecomposeAgent` | `WorkflowEngineTest` | `artifacts.json` → `Decomposition` |
| REQ-C-005 | Brownfield reasoning: modules, APIs, data flows | ADR-017 | `CodebaseScanner`, `ImpactAnalysisAgent` | `CodebaseScannerTest` (8) | `scenarios/brownfield/` — 131 files scanned, real paths reported |
| REQ-C-006 | Explicit dependency graph with entry/exit gates | ADR-007 | `sdlc.v1.json` (`dependsOn`/`branchCondition` = entry, `producesArtifacts` = exit), `DagValidator`, `WorkflowDefinitionSchema` | `DagValidatorTest` (8), `WorkflowDefinitionSchemaTest` (10), exit-gate tests (5) | `graph.mmd` per scenario |
| REQ-C-007 | Sequential and parallel paths with synchronisation | arch §4.1 | `WorkflowEngine.dispatchWave` | `sibling_nodes_run_concurrently...`, `a_join_node_does_not_start_until...` | overlapping timestamps in `history.json` |
| REQ-C-008 | Cross-stage context and decision lineage | arch §4 | `Artifact`, `StageContext`, `/lineage` | `WorkflowEngineTest` | `GET /{runId}/lineage/{artifactId}` |
| REQ-C-009 | Human approval checkpoints | ADR-008 | `WorkflowEngine.decide`, `gate-cli` | `a_run_parks_at_a_gate...`, `a_rejection_terminates_the_run...` | `gates.json` per scenario |
| REQ-C-010 | Bounded retries, fallback, rollback, safe stop | ADR-009, ADR-010 | `executeNodeWithRetries`, `applyRecovery` | 6 engine tests | `scenarios/brownfield/` rollback chain |
| REQ-C-011 | Policy guardrails: security, compliance, change control | ADR-012 | `policy-core`, `policy-set.v1.0.0.json` | `PolicyEvaluatorTest`, `DefaultPolicyChecksTest` (18) | `artifacts.json` → `PolicyEvaluation` |
| REQ-C-012 | Audit-grade observability and traceability | ADR-012 | `AuditHasher`, `JpaAuditSink` | `AuditHasherTest` (5) | `audit.jsonl`, `X-Audit-Chain` |
| REQ-C-013 | Success rate, retry/rollback frequency, MTTR, latency | arch §6 | `ReliabilityMetricsCalculator` | `ReliabilityMetricsCalculatorTest` (7) | `reliability-metrics.json` |
| REQ-C-014 | Dynamic replanning preserving governance | ADR-011 | `WorkflowEngine.replan` | `a_decision_that_supersedes...`, `the_downstream_closure_is_transitive...` | `scenarios/ambiguous/` at v2 |
| REQ-C-015 | Production-quality code, schemas, tests, docs | ADR-013 | whole repository; 5 JSON schemas under `specs/.../contracts/schemas/` | 189 unit/contract + 16 integration | this matrix |
| REQ-C-016 | Risks, trade-offs, failure scenarios, guardrails | summary §16-18 | — | — | final summary |
| REQ-C-017 | Controlled autonomy: agents execute, humans approve | ADR-008 | `NodeStateMachine`, `HumanGateAgent` | `an_elapsed_approval_window_safe_stops_and_never_approves` | no timer path to approval |
| REQ-C-018 | Final engineering summary | — | — | — | `final-engineering-summary.md` |
| REQ-C-019 | Three materially different scenarios | summary §12 | `demo-scenarios.sh` | — | three bundles under `docs/scenarios/` |
| REQ-C-020 | Architecture overview, setup, testing approach | — | — | — | `architecture.md`, `README.md` |
| REQ-C-021 | 2-3 day timebox | — | — | — | scope held to must-have; see §19 |

## Derived requirements (from the execution guide)

| ID | Requirement | Code | Test |
|---|---|---|---|
| REQ-D-003 | Every run records the policy version evaluated | `WorkflowInstance.policyVersion`, stamped on every journal and audit row | `the_audit_chain_for_a_completed_run_verifies` |
| REQ-D-004 | Outcomes are exactly PASS / FAIL / EXCEPTION_REQUESTED / NOT_APPLICABLE | `PolicyOutcome` | `PolicyEvaluatorTest` |
| REQ-D-005 | A failed mandatory policy blocks downstream progression | `PolicyEvaluator.Result.releaseBlocked`; `policy-eval` returns a node failure | `a_mandatory_failure_blocks_release_and_names_the_rule` |
| REQ-D-006 | Exceptions record policy, reason, scope, approver, control, expiry | `PolicyException`, `PolicyExceptionController` | `an_expired_exception_is_re_evaluated_as_a_failure` |
| REQ-D-007 | MTTR over recovered failures; unrecovered reported separately | `ReliabilityMetricsCalculator` | `mttr_averages_only_recovered_failures`, `an_unrecovered_failure_is_reported_separately...` |
| REQ-D-008 | Versioned contract plus schemas | `openapi.v1.yaml`; `workflow-definition`, `workflow-state`, `audit-event`, `policy-evaluation`, `approval-decision` schemas | `LinksApiContractTest`, `WorkflowDefinitionSchemaTest` |
| REQ-D-009 | A clear requirement proceeds without an artificial gate | `RequirementQualityAgent` | `a_well_specified_greenfield_requirement_proceeds_without_a_clarification_gate` |
| REQ-D-010 | Lack of response is never approval | `expireApprovals`, `NodeStateMachine` | `an_elapsed_approval_window_safe_stops_and_never_approves` |
| REQ-D-012 | Release decision is exactly one of three values | release gate decisions | `WorkflowEngineTest` |
| REQ-D-013 | Demonstration measurements labelled as such | `ReliabilityReport.DATA_CLASS` | `every_report_is_labelled_as_demonstration_data` |

## Assumptions, and where each one lives in code

| ID | Assumption | Where it is implemented |
|---|---|---|
| ASM-001 | Agents are deterministic behind a `StageAgent` seam | `orchestration-agents` |
| ASM-002 | Idempotency by header; same key + different body ⇒ 409 | `CreateLinkService.replayIfSeen` |
| ASM-003 | Base62, length 7, bounded collision retry | `ShortCodeGenerator`, `CreateLinkService.mint` |
| ASM-004 | Optional absolute expiry; expired ⇒ 410; no default | `ExpiryPolicy` |
| ASM-005 | Analytics eventually consistent, redirect never blocks | `QueuedClickRecorder` |
| ASM-006 | `X-Api-Key` on writes and the control plane | `ApiKeyFilter` |
| ASM-007 | 60/min create per key, 600/min redirect per address | `RateLimitFilter` |
| ASM-009 | 302, not 301, so caching does not defeat analytics | `RedirectController` |
| ASM-011 | 24h approval timeout ⇒ safe stop, never approval | `EngineSettings`, `expireApprovals` |
| ASM-012 | 3 attempts, exponential backoff, transient only | `WorkflowEngine.executeNodeWithRetries` |
| ASM-014 | Block loopback, link-local, RFC1918, ULA, CGNAT | `UrlValidator.isNonPublic` |

## Exclusions

| ID | Excluded | Stated in |
|---|---|---|
| EXC-001 | Multi-region / horizontal scaling | summary §19.7 |
| EXC-002 | User accounts, OAuth, multi-tenancy | ADR-015, summary §19.5 |
| EXC-003 | Custom vanity aliases | not implemented |
| EXC-004 | LLM-driven code generation inside the engine | ADR-005, summary §19.1-19.2 |

## Assignment clause map

A clause-by-clause reading of the assignment text lives in
[requirements-coverage.md](../assessment/requirements-coverage.md), including the gaps.

## Orphan check

- **Requirements with no test**: REQ-C-016, REQ-C-018, REQ-C-020, REQ-C-021 — documentation
  deliverables, verified by reading them.
- **Code with no requirement**: none. Every module maps to a row above.
- **Tests with no requirement**: none.
