# Final engineering summary

| Field | Value |
|---|---|
| Deliverable | Agentic Software Engineering System — URL Shortener |
| Date | 2026-09-29 |
| Release decision | **READY WITH ACCEPTED LIMITATIONS** (§21) |
| Tests | 205 — 189 unit/contract plus 16 integration — all passing |
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

Twelve Maven modules, 205 tests, no runtime dependencies.

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
| Entry **and** exit gates per node (ADR-007) | A stage that reports success without producing its declared output is a failure, and the engine can tell |
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

Run captured in [`docs/scenarios/greenfield/`](../scenarios/greenfield/); the run id changes on every execution.

Optional `expiresAt`. All five quality checks passed, so **the clarification gate was skipped with
its reasons recorded** (REQ-D-009). A transient failure injected into `contract-tests` was retried
once and recovered. Policy: `PASS:10 FAIL:0 EXC:1 NA:2` — the one exception request is the missing
licence report. Release approved. Outcome `COMPLETED`, 55 audit rows.

## 10. Scenario B — brownfield

Run captured in [`docs/scenarios/brownfield/`](../scenarios/brownfield/).

Base58 codes with legacy base62 codes still resolving. The **impact-analysis gate was reached
before any change**, and CC-003 would have blocked the release had the impact artifact been absent.
The `regression` node was failed deliberately, producing the full recovery path:

```
NodeFailed → RollbackStarted → RollbackCompleted → SafeStop → WorkflowResumed → NodeSucceeded
```

ADR-017 supersedes ADR-004. Policy: `PASS:11 FAIL:0 EXC:1 NA:1`. Outcome `COMPLETED`, 75 audit rows.

## 11. Scenario C — ambiguous

Run captured in [`docs/scenarios/ambiguous/`](../scenarios/ambiguous/).

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

205 tests, no Docker, about three minutes. Unit tests for the domain; `@DataJpaTest` slices for
persistence including a concurrent-collision test; `MockMvc` contract tests for the full API;
behavioural tests for the engine; ArchUnit for the module boundaries.

The engine tests are deliberately weighted towards **negative** cases — what the system refuses to
do is the governance claim.

Sixteen of them are genuine **integration** tests: `@SpringBootTest` on a random port, driven over
real HTTP with a real client. They exist because `MockMvc` stops short of the container — it does
not produce real redirect responses, does not exercise the filter chain the way Tomcat does, and
cannot show that the analytics queue eventually lands rows in the database. They drive a whole
governed run through the API, including parking at a gate, deciding it, replanning, and verifying
the audit chain afterwards.

## 14. Executed results

```
url-shortener-domain           37    orchestration-core             67
url-shortener-infrastructure    8    orchestration-agents           16
url-shortener-api              24    app (ArchUnit)                  7
telemetry                      12    app (integration, over HTTP)   16
policy-core                    18    ─────────────────────────────────
                                     TOTAL                         205
```

All passing. Reproduce with `./mvnw verify`.

## 15. Reliability metrics

From the three runs captured in [`reliability-metrics.json`](../scenarios/reliability-metrics.json),
derived from the journal on every request:

| Metric | Value |
|---|---|
| Runs | 3 terminal, 3 completed, success rate **1.0** |
| Node attempts | 44, of which 1 retry (frequency 0.023) |
| Failure events | 2, both recovered, 0 unrecovered |
| Rollbacks / compensations | 1 / 0 |
| **MTTR** | **883 ms** over 2 recovered failures (1766 ms total) |
| Recovery detail | `contract-tests` recovered by Retry in 1079 ms; `regression` by Rollback in 687 ms |
| End-to-end latency | p50 1409 ms, p95 2562 ms |
| Data class | `DEMONSTRATION` |

MTTR covers recovered failures only; unrecovered ones are reported separately. Folding them
together would make MTTR look best exactly when the system behaved worst.

**The counts are reproducible; the durations are not.** Attempts, retries, rollbacks and the
recovered/unrecovered split come out the same on every execution, because they follow from the
declared faults and the engine's rules. The millisecond figures are wall-clock on one laptop and
will differ on every run — quoting them as if they were constants would be the same category of
error as quoting them as production statistics.

**These are three scripted runs on one machine. They are not production statistics**, which is why
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
3. **No dependency vulnerability scanner, no secret scanner, no SBOM.** Reported as a gap raising
   an exception request. The demo supplies no scan results, so every bundle shows SEC-004 as
   `EXCEPTION_REQUESTED` rather than a pass.
4. **TDD evidence can only be supplied as a run input, never observed**, because the engine does
   not write the code. The demo supplies none, so TEST-001 shows as `EXCEPTION_REQUESTED`.
5. **The recorded approver is asserted, not authenticated** (ADR-015).
6. **The audit chain is tamper-evident, not tamper-proof.** Database access could recompute it.
7. **Single process, single node.** Rate-limit buckets and the click queue are process-local; both
   planes share a JVM and a datasource.
8. **Runs that were mid-flight when the process stopped are not resumed.** Finished runs rebuild
   from the database the first time they are requested, so every endpoint answers after a restart,
   but nothing picks interrupted work back up.
9. **DNS can change after creation**, so a host validated as public could later resolve privately.
10. **Reliability figures come from three runs on one machine**, labelled `DEMONSTRATION`.
11. **H2 concurrency differs from PostgreSQL**, so the concurrency test proves the code, not the engine.
12. **Brownfield impact analysis is a term search that follows references, not a semantic
    understanding of the code.** It reads the real repository and names real files, and it reports
    its own method and confidence. One consequence is visible in the brownfield bundle: the
    highest-scoring file is the agent source that *describes* alphabets, because the repository
    contains a description of itself. The scores and matched terms are published so a reviewer can
    see exactly why each file was listed.
13. **The git history is a build history, not a red-green-refactor history.** Tests and
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
- **Failsafe tests the packaged artifact, which here is a Spring Boot fat jar.** Its classes live
  under `BOOT-INF/`, so `@SpringBootTest` could not resolve its own meta-annotations and every
  integration test failed identically. Pointing Failsafe at the plain output directory fixed it.
- **The test-runner agent read the report of the test that was running it.** During the integration
  suite it picked up the in-flight Failsafe XML, parsed it as garbage, and failed the node. It now
  reads Surefire output only — a workflow grading the test that launched it is circular anyway.
- **A restart made most of a finished run unreadable.** The API served runs from an in-memory map,
  so after a restart `/{runId}`, `/artifacts`, `/graph` and `/gates` returned 404 and the metrics
  reported zero runs, while the docs claimed the opposite. Artifacts and facts are now persisted, a
  finished run is rebuilt from the database on demand, and metrics read the stored headers.
- **Artifact ids are only unique inside a run.** Every run mints an `ingest:RawRequirement:v1`, so
  using that as the artifact table's primary key let each run overwrite the last one's rows. Caught
  by querying the database rather than trusting the endpoint, which had been answering from memory.
- **The demo was feeding the system evidence that did not exist**: a clean dependency scan and a
  TDD reference pointing at files that were never written. For a project whose argument is "a gap is
  never reported as a pass", that was the sharpest contradiction in the repository. Both inputs are
  gone, and the shipped bundles now show three honest exception requests per run.
- **The application plane was answering for the control plane.** `ProblemDetailAdvice` had no
  `basePackages`, so a blank rationale on a governance endpoint came back as `URL_MALFORMED`. The
  e2e test missed it by asserting only the status code.
- **Lineage stopped at the release gate.** A gate produces no artifact, so walking only the direct
  dependencies left every downstream artifact with an empty provenance list, which made a claim in
  the reviewer guide false. Artifact-less dependencies are now looked through.

## 21. Release decision

**READY WITH ACCEPTED LIMITATIONS.**

Ready because: the prototype builds and runs from a clean clone with only a JDK; 205 tests pass;
all three scenarios executed end to end and are materially different; every governance guarantee
claimed is enforced by the state machine and covered by a negative test; all mandatory policies pass
on every run; and the evidence is captured output rather than written examples.

With accepted limitations because of §19 — chiefly that agents are deterministic rather than
LLM-backed, that `implement` does not write code, that no vulnerability scanner runs, and that
interrupted runs are not resumed. Each is disclosed in the artifact that would otherwise imply the
opposite.

Not ready for production, and nothing here claims to be.
