# Architecture

| Field | Value |
|---|---|
| Status | Implemented and executed |
| Date | 2026-09-29 |
| Stack | Java 21 · Spring Boot 3.3 · Maven multi-module · H2 (file) + Spring Data JPA + Flyway · JUnit 5 |
| Decisions | [docs/adr/](../adr/) — ADR-001 … ADR-017 |

---

## 1. Context

One JVM process, one H2 database file, one evidence folder. Nothing else is needed to run it.

```
  API consumer ────── GET /{code} (public) ──────────┐
  API consumer ────── X-Api-Key: POST /api/v1/links ─┤
  Engineer / approver ─ X-Api-Key: /api/v1/workflows ┤
  Reviewer ─────────── X-Api-Key (READ only) ────────┤
                                                     ▼
                          ┌──────────────────────────────────────────┐
                          │  Spring Boot host                        │
                          │  api-key filter · rate limit · headers   │
                          ├────────────────────┬─────────────────────┤
                          │ Application plane  │  Control plane      │
                          │ links · redirect   │  DAG engine · gates │
                          │ analytics · health │  policy · journal   │
                          ├────────────────────┴─────────────────────┤
                          │  telemetry: audit chain · reliability    │
                          ├──────────────────────────────────────────┤
                          │  H2 file database                        │
                          └──────────────────────────────────────────┘
```

| Aspect | Decision |
|---|---|
| Trust boundary | Only `GET /{code}` serves anonymous traffic. Every write and every control-plane route requires `X-Api-Key` (ADR-015). |
| Data ownership | The application plane owns `links`, `clicks`, `idempotency`. The control plane owns `workflow_*`, `approval_decisions`, `policy_exceptions`. Telemetry owns `audit_events`. No cross-plane writes. |
| External dependencies | None at runtime. |

---

## 2. Modules

| Module | Contains | Depends on |
|---|---|---|
| `contracts` | shared contract constants | — |
| `telemetry` | `AuditEvent`, `AuditHasher`, `ReliabilityMetricsCalculator` | contracts |
| `policy-core` | `PolicySet`, `PolicyEvaluator`, `DefaultPolicyChecks` | telemetry |
| `url-shortener-domain` | `ShortLink`, `UrlValidator`, `Alphabet`, `ExpiryPolicy`, ports | **nothing** |
| `url-shortener-infrastructure` | JPA entities, adapters, `QueuedClickRecorder`, Flyway | domain |
| `url-shortener-api` | controllers, application services, filters | domain, infrastructure |
| `orchestration-core` | `WorkflowEngine`, `NodeStateMachine`, `DagValidator`, model, ports | telemetry, policy-core |
| `orchestration-agents` | the stage agents | orchestration-core |
| `orchestration-infrastructure` | journal, approvals, audit sink, exception store | orchestration-core |
| `orchestration-api` | control-plane controllers, engine wiring | the three above |
| `gate-cli` | picocli approval client | — |
| `app` | Spring Boot host, ArchUnit tests | both plane APIs |

**The dependency rule**: application-plane modules never depend on control-plane modules, and vice
versa. Both may depend on `telemetry` and `contracts`. This is enforced by the Maven module graph
and asserted by `ArchitectureBoundaryTest` — seven rules, run on every build.

---

## 3. Application plane

### 3.1 Components

| Component | Responsibility |
|---|---|
| `LinksController` | HTTP to use case; RFC 9457 problem bodies; `API-Version` header |
| `RedirectController` | `GET /{code}` → 302, 404 or 410; offers a click to the recorder |
| `UrlValidator` | parse, canonicalise, scheme allowlist, length cap, credential rejection, host denylist, address checks |
| `ShortCodeGenerator` | `SecureRandom` base62/base58, length 7, alphabet injected |
| `ShortCodeValidator` | accepts every known alphabet for lookup |
| `CreateLinkService` | validate → idempotency → mint with bounded collision retry |
| `LinkQueryService` | resolve, fetch, delete, aggregate stats |
| `QueuedClickRecorder` | bounded queue drained by one virtual thread |
| `ApiKeyFilter` / `RateLimitFilter` / `SecurityHeadersFilter` | auth and abuse control |
| `HealthController` | `/health/live` (JVM), `/health/ready` (database + queue saturation) |

### 3.2 API contract v1.1.0

| Method and path | Auth | Success | Errors |
|---|---|---|---|
| `POST /api/v1/links` | WRITE | `201` (or `200` on an idempotent replay) | 400, 401, 409, 422, 429, 503 |
| `GET /{code}` | none | `302 Location` | 404 unknown, 410 expired, 429 |
| `GET /api/v1/links/{code}` | READ | `200` | 401, 404 |
| `GET /api/v1/links/{code}/stats` | READ | `200` | 400, 401, 404 |
| `DELETE /api/v1/links/{code}` | WRITE | `204` | 401, 404 |
| `GET /health/live`, `/health/ready` | none | `200` | 503 |

Errors are `application/problem+json` with a stable `errorCode`. The status codes carry meaning a
caller can act on: **404** means the code never existed, **410** means it existed and lapsed, **422**
means the URL parsed but policy refused the target, **409** means an `Idempotency-Key` was reused
with a different body.

### 3.3 Three decisions worth defending

**Validation happens at create time, not redirect time.** A host that resolves to a private address
never gets a short code, so there is nothing to follow later. The `Location` header is built from
the stored canonical target and nothing on the incoming request can influence it, which is what
keeps the redirect endpoint from becoming a way into the host network.

**302, not 301.** A permanent redirect would be cached by browsers and intermediaries, and clicks
that never reach the server cannot be counted.

**Analytics are off the redirect path.** Clicks go to a bounded queue drained by one writer. Counts
trail reality by up to about a second — the stats response says so in a `consistency` field — and
under a flood the queue drops events rather than making redirects wait. Losing a count is the
lesser harm.

---

## 4. Control plane

### 4.1 Entry and exit gates

Every node declares both.

Its **entry gate** is `dependsOn` plus `joinType` plus an optional `branchCondition`: the conditions
under which it may start. Its **exit gate** is `producesArtifacts`: what must exist before it may be
called successful. An agent reporting success is a claim; the declared artifact is the evidence.
Accepting the claim without the evidence is how a stage silently produces nothing and every
downstream node works from a gap that nothing reported — so a node that returns success without its
declared output is recorded as `ExitGateFailed` and treated as a permanent failure. It is not
retried, because repeating it would reproduce the same gap.

### 4.2 The scheduling rule

Everything else the engine does follows from one sentence:

> A node runs when every dependency it declared is satisfied, its branch condition holds, and
> nothing upstream of it has blocked.

Parallelism is what happens when that rule admits more than one node at once. A join is what
happens when it admits none until the last sibling finishes. A skipped branch is the rule declining
a node whose condition is false. None of these are separate features.

```
ingest → normalize → quality-check ─┬─▶ clarify 🛑 (only if ambiguous) ─┐
                                    ├─▶ impact-analysis (brownfield) ──▶ impact-approval 🛑 ─┤
                                    └──────────────────────────────────────────────────────▶ decompose
                                                                                               │
                                              ┌────────────────────────────────────────────────┴───┐
                                         contract-design                                      test-plan
                                              └────────────────────────┬───────────────────────────┘
                                                                  implement
                    ┌──────────────┬──────────────┬──────────────┬─────┴────────┐
               unit-tests    contract-tests   security-scan   regression      docs
                    └──────────────┴──────────────┴──────────────┴──────────────┘
                                                 ▼
                                            policy-eval ──▶ release-gate 🛑 ──▶ summary
```

The definition is [`sdlc.v1.json`](../../orchestration-core/src/main/resources/workflows/sdlc.v1.json).

### 4.3 The node state machine

`NodeStateMachine` holds one `EnumMap` of permitted transitions. Anything not in it throws.

This matters more than it looks. Several guarantees this system claims are not enforced by
scattered checks — they are enforced by the **absence of an edge**:

| Guarantee | How it is enforced |
|---|---|
| A gate cannot be approved without a decision | no edge `AWAITING_APPROVAL → SUCCEEDED`; the only way out is `APPROVED`, which `decide()` sets after writing the decision row |
| A failed node cannot become successful | no edge `FAILED → SUCCEEDED`; recovery states are the only route |
| A rejected gate is final | `REJECTED` has no outgoing edges at all |
| An invalidated node must re-execute | no edge `INVALIDATED → SUCCEEDED` |
| A skipped node cannot report success | no edge `SKIPPED → SUCCEEDED` |

A future change that wants one of these has to add it to the table, in the open, where
`NodeStateMachineTest` will see it.

### 4.4 Reliability

| Concern | Design |
|---|---|
| Classification | `TimeoutException`, `IOException` and known transient signatures ⇒ `TRANSIENT`. **Everything else, including unknown causes, ⇒ `PERMANENT`.** |
| Retry | Engine-owned, default 3 attempts, exponential backoff with ±20% jitter, transient only. Agents cannot retry internally. |
| Timeout | Per attempt, via `CompletableFuture.get(timeout)`. |
| Fallback | Declared per node. `docs` may complete degraded, which downgrades the run to `COMPLETED_WITH_LIMITATIONS`. |
| Rollback vs compensation | Declared per node, never inferred (ADR-010). Both end at a safe stop awaiting a human. |
| Safe stop | Permanent failure with no recovery, exhausted retries, approval timeout, operator request, or an unhandled engine error. |
| Resume | Returns safe-stopped **and blocked** nodes to pending — resuming a run that left its downstream subgraph blocked would look like a resume and behave like a no-op. |

Failing safe on unknown causes is deliberate: misclassifying a transient fault as permanent costs
one human decision, while the reverse burns the retry budget repeating something that cannot
succeed, and repeats any side effect along with it.

### 4.5 Human gates

| Gate | Armed when | Outcomes |
|---|---|---|
| `clarify` | `quality-check` reports material ambiguity | APPROVE (triggers replanning), REJECT, SAFE_STOP |
| `impact-approval` | input is brownfield | APPROVE, REJECT, SAFE_STOP |
| `release-gate` | after `policy-eval` | READY, READY_WITH_ACCEPTED_LIMITATIONS, NOT_READY |

The decision row is written **before** the node transitions, so a state change cannot exist without
the decision that justified it. `actor` and `rationale` are both required and refused when blank.

**The timeout only safe-stops.** There is no branch, flag or configuration anywhere that turns
elapsed time into consent, and `an_elapsed_approval_window_safe_stops_and_never_approves` asserts
it against the real engine.

### 4.6 Replanning

When a gate declares `supersedes`, its approval invalidates the transitive downstream closure of
that node, increments the definition version, and re-runs the affected nodes. Work that did not
consume the changed artifact keeps its result — and its approval.

Governance survives because the replan is itself a journalled event carrying the version diff and
the invalidated node list, policy evaluation always re-runs, and the new version stamps every
subsequent audit row.

Scenario C shows the whole path: the clarification decision supersedes `normalize`, which
invalidates `quality-check` and `clarify`, and the run completes at **definition v2**.

---

## 5. Policy engine

Thirteen rules across five domains in [`policy-set.v1.0.0.json`](../../policies/policy-set.v1.0.0.json).
Outcomes are exactly `PASS`, `FAIL`, `EXCEPTION_REQUESTED`, `NOT_APPLICABLE`.

A **mandatory FAIL** blocks the release and, because `policy-eval` returns it as a node failure, the
release gate is blocked by the engine's own dependency rule rather than by a human noticing a field
in a report.

Two distinctions the check implementations take seriously:

- **Absence of evidence is not evidence of absence.** No dependency scan ran ⇒
  `EXCEPTION_REQUESTED`, which a human must decide. It never reports a clean scan that did not happen.
- **A rule that does not apply is not a pass.** `NOT_APPLICABLE` is counted separately so it cannot
  inflate the pass count.

An exception must name a compensating control and an expiry. An expired exception is re-evaluated
as a plain `FAIL` — it does not decay into a pass.

---

## 6. Observability and metrics

`audit_events` is append-only with a per-run SHA-256 hash chain; the port exposes only append and
read, so append-only is a property of the type. `GET /{runId}/audit` returns JSON Lines and an
`X-Audit-Chain` header saying whether the chain verifies.

`ReliabilityMetricsCalculator` derives everything from the journal on every request — nothing is
stored or hand-maintained.

**MTTR is the mean over recovered failures only.** Unrecovered failures are reported separately
rather than folded in. Mixing them would make MTTR look better exactly when the system behaved
worse. When nothing was recovered, MTTR is `null`, not zero.

Every response carries `"dataClass": "DEMONSTRATION"`.

---

## 7. Testing

| Module | Tests | Covers |
|---|---:|---|
| `url-shortener-domain` | 37 | validation, SSRF address checks, alphabets, expiry, hashing |
| `url-shortener-infrastructure` | 8 | migrations, round-trips, concurrent collision, daily aggregation |
| `url-shortener-api` | 24 | full API contract, idempotency, auth scopes, expiry, health |
| `telemetry` | 12 | MTTR including the unrecovered mix, hash-chain tamper detection |
| `policy-core` | 18 | outcomes, mandatory blocking, exception expiry, the shipped rule set |
| `orchestration-core` | 59 | schema and DAG validation, prohibited transitions, entry and exit gates, parallelism, joins, retry bounds, timeout, fallback, rollback vs compensation, replanning |
| `orchestration-agents` | 16 | the clarification decision against all three scenario texts; codebase scanning for brownfield impact |
| `app` | 7 | ArchUnit module boundaries |
| `app` (integration) | 16 | both planes end to end over real HTTP on a real port |
| **Total** | **197** | |

---

## 8. Layout

```
├── docs/adr/ architecture/ assessment/ operations/ scenarios/ traceability/
├── policies/policy-set.v1.0.0.json
├── specs/001-agentic-url-shortener/contracts/
├── scripts/demo-scenarios.sh
├── contracts/ telemetry/ policy-core/
├── url-shortener-domain/ -infrastructure/ -api/
├── orchestration-core/ -agents/ -infrastructure/ -api/
├── gate-cli/  app/
└── pom.xml  mvnw  README.md  SECURITY.md
```

---

## 9. Quality attributes and how each is verified

| Attribute | Verified by |
|---|---|
| Modular | Maven module graph + `ArchitectureBoundaryTest` (7 rules) |
| Testable | domain has zero framework dependencies; every component behind an interface |
| Reliable | `WorkflowEngineTest` — retry bounds, timeout, fallback, rollback, compensation, safe stop, resume |
| Secure | `UrlValidatorTest`, `LinksApiContractTest`; threat model in `threat-model.md` |
| Observable | `/audit` reconstructs a run; `/actuator/prometheus`; `/metrics/reliability` |
| Auditable | hash-chained append-only trail; actor on every decision |
| Governed | policy set versioned per run; gates cannot self-approve; replanning journalled |
