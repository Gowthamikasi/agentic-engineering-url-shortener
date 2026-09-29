# Requirements coverage

The assignment, clause by clause, against what exists in this repository. Each row names where the
behaviour lives and how to check it. Gaps are listed as gaps.

| Legend | |
|---|---|
| **Met** | implemented, tested, and demonstrable |
| **Met, bounded** | implemented, with a stated limit |
| **Not built** | deliberately out of scope, stated in §19 of the final summary |

---

## §4.1 Requirement understanding

> *Interpret intent, identify ambiguity, normalize into a clear engineering problem.*

**Met.**

| What | Where | Check |
|---|---|---|
| Intake and classification | `RequirementIngestAgent` | `ingest:RawRequirement` artifact in any bundle |
| Normalisation into testable statements | `RequirementNormalizeAgent` | `normalize:NormalizedRequirement` |
| Ambiguity detection across five checks | `RequirementQualityAgent` | `RequirementQualityAgentTest` (8 tests) |

The discriminating behaviour is that it is **not** uniformly suspicious: a complete requirement
passes without a gate, a vague one stops the run. Both directions are tested against all three
scenario texts, because a system that asks a question every time trains reviewers to approve
without reading.

## §4.2 Task decomposition

> *Convert high-level requirements into actionable tasks with dependencies and sequencing.*

**Met.** `DecomposeAgent` emits ordered tasks with `dependsOn` and a `tddRequired` flag; the flag
feeds policy TEST-001. See `decompose:Decomposition` in any bundle.

## §4.3 Codebase reasoning (brownfield)

> *Identify impacted modules/services/APIs/data flows and demonstrate architectural understanding.*

**Met, bounded.** `CodebaseScanner` reads the actual repository:

1. derives significant terms from the requirement, discarding filler words;
2. scores every main source file by how many distinct terms it mentions;
3. follows references — a file using an impacted type is impacted too, which is how
   `ShortCodeGenerator` is found for an alphabet change despite barely matching the wording;
4. classifies each hit as `api`, `data`, `domain`, `config` or `internal`, which is what produces
   the API-surface and data-flow sections of the report.

**The bound, stated in the report itself:** this is a term search that follows references, not a
semantic understanding of the code. When nothing matches it says so and asks the human to scope the
change at the gate, rather than emitting a plausible-looking guess — a fabricated impact report
reads exactly like a real one at an approval gate, which is what makes it dangerous.

Checked by `CodebaseScannerTest` (8 tests, against a temporary repository so the assertions survive
renames here).

## §4.4 Workflow orchestration — the critical differentiator

> *Explicit dependency graph with entry/exit gates; sequential and parallel paths with
> synchronization; cross-stage context and decision lineage; human approval checkpoints; bounded
> retries, fallback, rollback, safe-stop; policy guardrails; audit-grade observability; reliability
> metrics; dynamic re-planning.*

**Met.** Taken clause by clause:

| Clause | Implementation | Proof |
|---|---|---|
| Explicit dependency graph | Versioned JSON, schema-validated then DAG-validated at load | `WorkflowDefinitionSchemaTest`, `DagValidatorTest` |
| **Entry** gates | `dependsOn` + `joinType` + `branchCondition`, evaluated read-only | `computeReadySet`; branching tests |
| **Exit** gates | `producesArtifacts` — a node that reports success without its declared output **fails** | `a_node_that_reports_success_without_its_declared_artifact_fails_the_exit_gate` |
| Sequential and parallel paths | Ready-set dispatch on virtual threads, bounded by a semaphore | `sibling_nodes_run_concurrently_and_their_execution_windows_overlap` |
| Synchronisation | A join node becomes ready only when the last dependency settles | `a_join_node_does_not_start_until_every_dependency_has_finished`; also asserted over HTTP in `ControlPlaneE2ETest` |
| Cross-stage context | `StageContext` carries artifact references, decisions, assumptions, facts | `GET /{runId}/artifacts` |
| Decision lineage | Each artifact records its inputs and the decisions in force; lineage survives gates | `GET /{runId}/lineage/{artifactId}` walks 13 steps to the raw requirement |
| Human approval checkpoints | Three gates; decision written before the transition; actor and rationale required | `WorkflowEngineTest` gate tests |
| Bounded retries | Engine-owned, transient only, exponential backoff with jitter | `the_retry_budget_is_bounded_and_the_node_stops_when_it_is_exhausted` |
| Fallback | Declared per node; degrades the run outcome rather than hiding | `a_node_with_a_fallback_completes_degraded_rather_than_stopping_the_run` |
| Rollback **and** compensation | Classified per node, never inferred | `a_rollbackable_node_rolls_back_and_a_compensatable_node_compensates` |
| Safe-stop | Permanent failure, exhausted retries, approval timeout, operator request, engine error | multiple engine tests |
| Policy guardrails | 13 rules, five domains, versioned and stamped per run | `PolicyEvaluatorTest`, `DefaultPolicyChecksTest` |
| Audit-grade observability | Append-only, hash-chained, `X-Audit-Chain` verification header | `AuditHasherTest`; verified live in `ControlPlaneE2ETest` |
| Success rate | from the journal | `/api/v1/metrics/reliability` |
| Retry / rollback frequency | from the journal | same |
| **MTTR** | recovered failures only; unrecovered reported separately | `mttr_averages_only_recovered_failures` |
| End-to-end latency | p50 / p95 from run records | same |
| Dynamic re-planning | Invalidate the downstream closure, bump the version, regenerate | `answering_the_clarification_replans_the_run_onto_a_new_definition_version` |
| Governance preserved during re-planning | Replan journalled with the diff; policy always re-runs; version stamps every later row | ambiguous bundle, definition v2 |
| Non-linear, stateful execution | Branching, skipping, joining, parking, replanning — all in one run | ambiguous scenario |

The clause worth singling out is **non-linear**: the ambiguous run branches on a fact produced
mid-run, parks indefinitely on a human, re-executes a node that had already succeeded, and finishes
on a different definition version than it started. That is not a linear chain with retries.

## §4.5 Engineering output generation

> *Production-quality code, API/schema definitions, unit/integration tests, supporting documentation.*

**Met.**

| Required | Delivered |
|---|---|
| API definitions | `openapi.v1.yaml`, served live at `/v3/api-docs` |
| Schema definitions | Five JSON schemas under `specs/.../contracts/schemas/`. The workflow-definition schema is **enforced at load**, not decorative |
| Unit tests | 181 across 8 modules |
| **Integration tests** | 16 over real HTTP on a real port (`ApplicationPlaneE2ETest`, `ControlPlaneE2ETest`), run by Failsafe in `verify` |
| Documentation | README, architecture, threat model, 17 ADRs, runbook, reviewer guide, traceability, this document |

## §4.6 Validation and risk control

> *Identify risks/trade-offs/failure scenarios and define validation and safety guardrails.*

**Met.** Risk register and trade-off table in the final summary §16–§17; failure scenarios are not
just listed but **executed** — an injected transient failure recovers by retry, an injected
permanent failure rolls back and waits for a human. Guardrails are in §18, including the specific
measures against fabricated evidence.

## §4.7 Controlled autonomy

> *Agents execute multi-step work; humans provide oversight, approvals, and final quality control.*

**Met.** The boundary is in the type system and the state machine, not in convention:

- `StageContext` carries artifact references and facts — no datasource, no repository, no key. An
  agent cannot reach outside its stage.
- A gate node is never dispatched to an agent; `HumanGateAgent` fails loudly if one ever is.
- `AWAITING_APPROVAL` has no state-machine edge to `SUCCEEDED`. The only route is `APPROVED`, which
  is set after the decision row is written.
- The approval timeout leads only to `SAFE_STOPPED`. There is no path from elapsed time to consent.

## §4.8 Final engineering summary

> *Plan/rationale, artifacts, risks/trade-offs/validation, assumptions, limitations.*

**Met.** [`final-engineering-summary.md`](final-engineering-summary.md), 21 sections, including a
section on what went wrong during the build and twelve named limitations.

---

## §5 Deliverables

| Deliverable | Status |
|---|---|
| Working prototype, runnable end to end | `./mvnw verify` then one `java -jar`; JDK 21 is the only prerequisite |
| Architecture overview | [`architecture.md`](../architecture/architecture.md) plus 17 ADRs |
| Three scenarios with decomposition, orchestration, validation | [`docs/scenarios/`](../scenarios/) — executed, captured, not written |
| Setup instructions | README and `quickstart.md`, verified from a clean clone |
| Testing approach, limitations, trade-offs | Final summary §13–§14, §17, §19 |

## §3 Scope coverage

| Scope item | Covered by |
|---|---|
| Greenfield | Scenario A |
| Brownfield | Scenario B, including real codebase scanning |
| Test and documentation improvements | The `test-plan` and `docs` nodes run in every scenario; `docs` is the designated non-blocking node that can complete degraded. **Not demonstrated as a scenario of its own** |
| Well-defined requirements | Scenarios A and B |
| Ambiguous requirements | Scenario C |

---

## Known gaps

Listed here rather than left for a reviewer to discover:

1. **Agents are deterministic, not LLM-backed.** `StageAgent` is the seam; the adapter is not built.
2. **`implement` records a change set; it does not write code.** Every artifact it produces says so.
3. **No dependency vulnerability scanner, secret scanner or SBOM.** Reported as a gap that raises a
   policy exception request, never as a clean scan.
4. **TDD evidence is supplied as a run input, not observed.** The engine cannot watch a test go red
   then green because it does not write the code. Absent, TEST-001 raises an exception request.
5. **In-flight runs are not rehydrated after a restart.** The journal is durable and is the source
   of truth; the rebuild-on-boot path is not implemented.
6. **The recorded approver is asserted, not authenticated** (ADR-015).
7. **The audit chain is tamper-evident, not tamper-proof.**
8. **No scenario dedicated to test/documentation improvement**, as noted in the scope table above.
9. **Single process, single node.** Rate-limit buckets and the click queue are process-local.

The full list, with reasoning, is in the final summary §19.
