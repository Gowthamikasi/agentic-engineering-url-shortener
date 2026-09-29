# Threat model

STRIDE over the two planes. Each row names the mitigation and, where one exists, the test that
proves it. Rows with no test are marked as such rather than quietly listed as covered.

## Assets

| Asset | Why it matters |
|---|---|
| The redirect mapping | A tampered target silently sends every visitor somewhere else. |
| The audit trail | It is the only record of who decided what. If it can be edited, none of the governance claims survive. |
| API keys | They separate reading a run from approving its release. |
| The workflow definition | It decides what runs, in what order, behind which gates. |
| Click analytics | Deliberately holds no PII, so it stays a low-value target. |

## The redirect endpoint

It is the only route serving anonymous traffic, and its entire job is to send a caller elsewhere.
Two properties keep it from becoming a liability:

1. **Validation happens at create time.** A host resolving to a loopback, link-local, RFC1918, ULA
   or carrier-grade-NAT address never receives a short code, so there is nothing to follow later.
2. **The `Location` value comes from storage.** Nothing on the incoming request influences where a
   redirect goes, so the endpoint cannot be turned into an open redirector or an SSRF proxy.

## STRIDE

### Spoofing

| Threat | Mitigation | Test |
|---|---|---|
| Calling a write or control route without authorisation | `X-Api-Key` required; SHA-256 hashes only; `MessageDigest.isEqual`; every configured key compared so timing does not reveal which matched | `LinksApiContractTest` |
| A read-only caller approving a release | READ / WRITE / CONTROL scopes enforced per route | `LinksApiContractTest` |
| Claiming to be another approver | **Not mitigated.** An API key cannot prove who is holding it; the recorded actor is asserted, not authenticated. Listed in the limitations. | — |

### Tampering

| Threat | Mitigation | Test |
|---|---|---|
| Editing an audit row to hide a decision | Per-run SHA-256 hash chain; each row commits to the previous hash, so an edit breaks every hash after it | `AuditHasherTest` |
| Deleting a row from the middle of a run | Same chain; verification reports the row where it breaks | `AuditHasherTest` |
| Repointing an existing short link | `saveIfAbsent` uses `persist`, not `save`: an assigned-id entity would otherwise be *merged*, silently overwriting the row instead of reporting the collision | `ShortLinkPersistenceTest` |
| Schema drift away from the reviewed migrations | Flyway owns the schema; Hibernate is `ddl-auto: none` | — |
| A workflow definition used to run arbitrary code | Branch conditions evaluate in a `SimpleEvaluationContext` restricted to read-only property access — no method calls, no type construction; agent types must resolve to registered beans | `WorkflowEngineTest` |

Recomputing the whole chain from database access would go undetected. The trail is
tamper-**evident**, not tamper-**proof**; detecting that needs an external anchor, which is out of scope.

### Repudiation

| Threat | Mitigation | Test |
|---|---|---|
| "I never approved that" | The decision row is written before the node transitions, carrying actor, rationale and timestamp; a state change cannot exist without it | `WorkflowEngineTest` |
| An approval with no reason | `actor` and `rationale` are both required and refused when blank | `WorkflowEngineTest` |
| A silent state change | Every transition is journalled before it is applied | `WorkflowEngineTest` |

### Information disclosure

| Threat | Mitigation | Test |
|---|---|---|
| Enumerating every short link | Codes are random over a 62^7 space, not sequential (ADR-004 rejected a counter for exactly this) | `ShortCodeTest` |
| Reading another caller's statistics | Stats require a READ scope | `LinksApiContractTest` |
| Leaking the short URL to the target site | `Referrer-Policy: no-referrer` on every redirect | `LinksApiContractTest` |
| A key reaching a log file | Logback masks key, token, password and authorization patterns in both appenders | — |
| Building a visitor profile from clicks | Only code, timestamp, referer **host** and a coarse UA class are stored — never an IP, a full user agent or a full referer | `ShortLinkPersistenceTest` |

### Denial of service

| Threat | Mitigation | Test |
|---|---|---|
| Flooding link creation | 60/minute per key | — |
| Flooding redirects | 600/minute per address | — |
| Overwhelming the click writer | Bounded queue; drops with a counter rather than applying back-pressure; saturation surfaces on `/health/ready` | — |
| A node hanging the engine | Per-attempt timeout and bounded parallelism | `WorkflowEngineTest` |
| An unbounded retry loop | Retry is engine-owned and bounded; agents cannot retry internally | `WorkflowEngineTest` |
| A cyclic definition stalling the scheduler | Rejected at load by `DagValidator` | `DagValidatorTest` |
| An oversized URL | 2048-character cap, 8KB header cap | `UrlValidatorTest` |

Rate-limit buckets are process-local: they do not survive a restart or span instances.

### Elevation of privilege

| Threat | Mitigation | Test |
|---|---|---|
| An agent reaching the database or a secret | `StageContext` carries artifact references and facts only — no `DataSource`, no repository, no key. The boundary is in the type, not in convention | — |
| Waiting out a gate to get it approved | The timeout produces `SAFE_STOPPED`. No code path leads from elapsed time to an approved state | `WorkflowEngineTest` |
| Skipping the release gate | `summary` depends on `release-gate`; a blocked gate blocks it | `WorkflowEngineTest` |
| An unexpected admin surface | Actuator limited to health, info and prometheus; H2 console disabled | — |

## Residual risks

1. **DNS can change after creation.** A host that resolved publicly could later point somewhere
   private. Re-validating on every redirect would add a DNS lookup to the hot path; the trade is
   recorded rather than hidden.
2. **The recorded approver is asserted, not proven** (ADR-015).
3. **The hash chain is tamper-evident only.**
4. **No dependency vulnerability scanner runs.** The security agent reports this as a gap and the
   policy set raises an exception request for a human to decide. It does not report a clean scan.
5. **Both planes share a JVM and a datasource**, so a runaway workflow could starve the shortener.
