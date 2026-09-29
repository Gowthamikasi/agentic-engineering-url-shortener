# Final engineering summary

| Field | Value |
|---|---|
| Deliverable | Agentic Software Engineering System — URL Shortener |
| Date | 2026-09-29 |
| Release decision | **READY WITH ACCEPTED LIMITATIONS** (§21) |
| Tests | 157, all passing |
| Scenarios executed | 3 of 3, all `COMPLETED` |

Everything reported here was produced by executing the system. Where something was not executed,
it is named as not executed rather than left ambiguous.

---

## 1. What was asked for

Build a governed, stateful, non-linear agentic SDLC orchestration system, demonstrated on a URL
shortener across three materially different scenarios, delivered as a runnable prototype with
evidence a reviewer can verify.

The shortener is the demonstration domain. The assessed subject is the control plane.

## 2. What was built

Two planes in one process, sharing nothing above the database:

- **Application plane** — a URL shortener: create, resolve, expire, analytics, validation, health.
- **Control plane** — a DAG orchestration engine with persisted state, parallel paths with
  synchronisation, entry and exit gates, human approval checkpoints, bounded retries, fallback,
  rollback and compensation, safe stop, resume, dynamic replanning, versioned policy guardrails, a
  hash-chained audit trail, and reliability metrics including MTTR.

Twelve Maven modules, 157 tests, no runtime dependencies.

## 3. Requirement interpretation

The assignment's own framing — *"a linear sequence of agents is not sufficient evidence of
orchestration"* — set the design. The work went into the parts that are only visible when something
goes wrong: what happens at a gate, how a failure is classified, what a replan preserves.

Ambiguities were recorded as ASM-001 … ASM-014 with a proposed decision each, rather than resolved
silently. The two that shaped the most code were ASM-001 (agents are deterministic) and ASM-005
(analytics are eventually consistent).

## 4. Task decomposition

Work packages ran in dependency order: contracts and telemetry first (both planes depend on them),
then the domain, then persistence, then the API; in parallel, the policy engine and the
orchestration core; then the agents, the control-plane API, and the host. Documentation and
evidence last, because evidence can only be captured after execution.

The two synchronisation points were **the host** (both planes must exist) and **the scenario run**
(the whole system must work before any evidence exists).

## 5. Architecture

See [architecture.md](../architecture/architecture.md). The decisions with the most leverage:

| Decision | Why it matters |
|---|---|
| Two planes, neither depending on the other (ADR-001) | The separation is a fact the build enforces, not a claim |
| A custom DAG engine (ADR-005) | A framework would answer the assessed questions with its own semantics |
| Journal as source of truth (ADR-006) | Every interesting question is a question about history |
| Definition as versioned data (ADR-007) | The governed path is readable without reading the engine |
| Per-node recovery classification (ADR-010) | Inferring it is how an audit trail starts lying |

## 6. Orchestration model

One rule produces the behaviour: *a node runs when its dependencies are satisfied, its branch
condition holds, and nothing upstream has blocked.* Parallelism, joins and skipped branches all
fall out of it.

The `NodeStateMachine` table is the governance model. Several guarantees are enforced by the
**absence of an edge** rather than by a check that could be forgotten: `AWAITING_APPROVAL` has no
edge to `SUCCEEDED`, `FAILED` has no edge to `SUCCEEDED`, and `REJECTED` has no outgoing edges at all.

## 7. Human-in-the-loop

Three gates: clarification (armed only on real ambiguity), impact approval (brownfield only),
release. The decision row is written before the node transitions, and both `actor` and `rationale`
are required.

The approval timeout **only** safe-stops. There is no branch anywhere that turns silence into
consent, and a test asserts it against the real engine.

## 8. Policy guardrails

Thirteen rules across security, compliance, licensing, change control and testing, versioned as
`policy-set.v1.0.0.json` and stamped on every run.

Two distinctions the checks take seriously:

- **A gap is not a pass.** No dependency scan ran ⇒ `EXCEPTION_REQUESTED`, decided by a human. The
  security agent never reports a clean scan that did not happen.
- **Not applicable is not a pass.** Counted separately so it cannot inflate the pass total.

An exception must carry a compensating control and an expiry, and an expired one is re-evaluated as
a `FAIL`.

## 9. Scenario A — greenfield

Run `run_1a0ed63044f_1` — [evidence](../scenarios/greenfield/).

Optional `expiresAt`. All five quality checks passed, so **the clarification gate was skipped with
its reasons recorded** (REQ-D-009). A transient failure injected into `contract-tests` was retried
once and recovered. Policy: `PASS:10 FAIL:0 EXC:1 NA:2` — the one exception request is the missing
licence report. Release approved. Outcome `COMPLETED`, 55 audit rows.

## 10. Scenario B — brownfield

Run `run_1a0ed631050_2` — [evidence](../scenarios/brownfield/).

Base58 codes with legacy base62 codes still resolving. The **impact-analysis gate was reached
before any change**, and CC-003 would have blocked the release had the impact artifact been absent.
The `regression` node was failed deliberately, producing the full recovery path:

```
NodeFailed → RollbackStarted → RollbackCompleted → SafeStop → WorkflowResumed → NodeSucceeded
```

ADR-017 supersedes ADR-004. Policy: `PASS:11 FAIL:0 EXC:1 NA:1`. Outcome `COMPLETED`, 75 audit rows.

## 11. Scenario C — ambiguous

Run `run_1a0ed631846_3` — [evidence](../scenarios/ambiguous/).

*"Links should expire after a while and we should show popular links."* The quality agent found
four ambiguities and the run **genuinely stopped** at `AWAITING_CLARIFICATION` with `implement` at
zero attempts. It did not guess.

The human answer superseded the normalized requirement, which invalidated `quality-check` and
`clarify` and regenerated them under **definition v2**. Policy: `PASS:9 FAIL:0 EXC:2 NA:2` — the
second exception request is the missing TDD evidence, correctly raised because this run supplied
none. Outcome `COMPLETED` at v2, 65 audit rows.

## 12. Why the three differ materially

| Dimension | A | B | C |
|---|---|---|---|
| Clarification gate | skipped with reasons | skipped | **triggered, run paused** |
| Impact gate | — | **required first** | — |
| Failure path | transient → retry | permanent → rollback → resume | — |
| Definition version | 1 | 1 | **2** |
| Governance artifact | release decision | impact approval + ADR-017 | clarification decision + replan |

## 13. Testing approach

157 tests, no Docker, about two minutes. Unit tests for the domain; `@DataJpaTest` slices for
persistence including a concurrent-collision test; `MockMvc` contract tests for the full API;
behavioural tests for the engine; ArchUnit for the module boundaries.

The engine tests are deliberately weighted towards **negative** cases — what the system refuses to
do is the governance claim.

## 14. Executed results

```
url-shortener-domain           37    orchestration-core             43
url-shortener-infrastructure    8    orchestration-agents            8
url-shortener-api              24    app (ArchUnit)                  7
telemetry                      12    ─────────────────────────────────
policy-core                    18    TOTAL                         157
```

All passing. Reproduce with `./mvnw verify`.

## 15. Reliability metrics

From three executed runs, derived from the journal on every request:

| Metric | Value |
|---|---|
| Runs | 3 terminal, 3 completed, success rate **1.0** |
| Node attempts | 44, of which 1 retry (frequency 0.023) |
| Failure events | 2, both recovered, 0 unrecovered |
| Rollbacks / compensations | 1 / 0 |
| **MTTR** | **631 ms** over 2 recovered failures (1262 ms total) |
| End-to-end latency | p50 1148 ms, p95 2060 ms |
| Data class | `DEMONSTRATION` |

MTTR covers recovered failures only; unrecovered ones are reported separately. Folding them
together would make MTTR look best exactly when the system behaved worst.

**These are three scripted runs on one laptop. They are not production statistics**, which is why
every response says so.

## 16. Risks

| Risk | Severity | Mitigation |
|---|---|---|
| A short link stops resolving after an alphabet change | High | Minting narrows, lookup stays wide; regression test must pass before and after (ADR-017) |
| A permanent failure retried as transient repeats a side effect | High | Unknown causes classified `PERMANENT`; agents may escalate but never de-escalate |
| A gate approved without a human | High | No state-machine edge permits it; the timeout only safe-stops |
| An audit row edited to hide a decision | Medium | Per-run hash chain; tamper-evident, not tamper-proof |
| A cyclic definition stalls the scheduler | Medium | Rejected at load by `DagValidator` |
| A runaway workflow starves the shortener | Medium | Bounded parallelism and per-node timeouts; shared JVM remains a limitation |
| Click loss under load | Low | Bounded queue drops with a counter; surfaced on the readiness probe |

## 17. Trade-offs

| Chose | Over | Because |
|---|---|---|
| Custom engine | Temporal / Durable Task | A framework hides the behaviours being assessed behind its own semantics |
| H2 file mode | PostgreSQL in Docker | Every prerequisite is a way for a reviewer to get a failure that says nothing about the work |
| Eventually consistent analytics | Synchronous writes | Redirect latency is the product; a click count is not |
| Fail safe on unknown failures | Optimistic retry | One extra human decision beats a repeated side effect |
| Deterministic agents | LLM-backed agents | Repeatable tests and real evidence; the LLM seam exists and is not half-built |
| 302 | 301 | A cached redirect is an uncounted click |
| Invalidate the closure | Restart the run | Restarting discards approvals nothing has invalidated |

## 18. Guardrails against fabricated evidence

This was the sharpest constraint, and it shaped the code:

- The `TestRunnerAgent` parses the build's **real** Surefire and Failsafe XML. No matching report
  ⇒ the node **fails**. It cannot report a pass it did not observe.
- The security agent reports that **no dependency scan ran**, rather than a clean scan.
- The `implement` agent's own artifact states that it records a change set and does not synthesise code.
- Injected faults are marked `(fault-injected)` in the audit trail and remain in the metrics population.
- The demo script **reports a refused decision** instead of printing success.
- Reliability numbers are recomputed from the journal per request; there is nowhere to hand-enter one.

## 19. Limitations

Stated plainly, because a prototype that overclaims is worse than one that is modest:

1. **Agents are deterministic, not LLM-backed** (ASM-001, EXC-004). `StageAgent` is the seam.
2. **`implement` does not write code.** It records a rollbackable change set.
3. **No dependency vulnerability scanner, no secret scanner, no SBOM.** Reported as a gap, raising
   an exception request.
4. **TDD evidence is supplied as a run input, not observed.** Absent, TEST-001 raises an exception
   request. The engine cannot watch a test go red and then green because it does not write the code.
5. **The recorded approver is asserted, not authenticated** (ADR-015).
6. **The audit chain is tamper-evident, not tamper-proof.** Database access could recompute it.
7. **Single process, single node.** Rate-limit buckets and the click queue are process-local; both
   planes share a JVM and a datasource.
8. **Live runs are held in memory** alongside the journal. The journal is durable and is the source
   of truth, but automatic rehydration of in-flight runs after a restart is **not implemented** —
   the durable state exists, the rebuild-on-boot path does not.
9. **DNS can change after creation**, so a host validated as public could later resolve privately.
10. **Reliability figures come from three runs on one machine**, labelled `DEMONSTRATION`.
11. **H2 concurrency differs from PostgreSQL**, so the concurrency test proves the code, not the engine.
12. **The git history is a build history, not a red-green-refactor history.** Tests and
    implementation were written together in one session. Where a defect was found by a test, that
    sequence is real and is described in §20; a general TDD claim over every task is not made.

## 20. What went wrong during the build

Included because a summary with no failures in it is not a summary of real work:

- **`save()` silently merged instead of inserting.** With an assigned string id, Spring Data treats
  the entity as detached and *merges* it, so a duplicate short code overwrote the existing row
  instead of reporting a collision. The concurrency test caught it; the fix was `persist` plus an
  explicit flush.
- **`REQUIRES_NEW` escaped test rollback.** Committing collision checks in their own transaction is
  correct for production and breaks `@DataJpaTest` isolation. The tables are now truncated
  explicitly rather than relying on a rollback that does not apply.
- **The quality agent flagged a perfectly clear requirement.** Its "show" heuristic fired on *"stats
  must still show total clicks"* — the artificial gate REQ-D-009 exists to prevent. Found by running
  Scenario A, fixed, and pinned by `RequirementQualityAgentTest` against all three scenario texts.
- **The logging config took the application down twice.** A comma and then an apostrophe inside the
  secret-masking regex each terminated Logback's option parsing early. Both are now called out in a
  comment in the file.
- **Nested Spring Data repository interfaces were not registered**, and nested JPA entities needed
  explicit entity names before JPQL could refer to them.

## 21. Release decision

**READY WITH ACCEPTED LIMITATIONS.**

Ready because: the prototype builds and runs from a clean clone with only a JDK; 157 tests pass;
all three scenarios executed end to end and are materially different; every governance guarantee
claimed is enforced by the state machine and covered by a negative test; all mandatory policies pass
on every run; and the evidence is captured output rather than written examples.

With accepted limitations because of §19 — chiefly that agents are deterministic rather than
LLM-backed, that `implement` does not write code, that no vulnerability scanner runs, and that
in-flight runs are not rehydrated after a restart. Each is disclosed in the artifact that would
otherwise imply the opposite.

Not ready for production, and nothing here claims to be.
