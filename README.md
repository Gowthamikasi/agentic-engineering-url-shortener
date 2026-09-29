# Agentic Software Engineering System — URL Shortener

A locally runnable prototype of a **governed, stateful, non-linear agentic SDLC orchestration
system**, demonstrated against a production-oriented **URL shortener**.

The shortener is the demonstration domain. The thing being built is the control plane: an engine
that takes a requirement and drives it through requirements → design → implementation → testing →
documentation → release readiness as an explicit dependency graph, under human oversight, with
every state change journalled.

| | |
|---|---|
| Stack | Java 21 · Spring Boot 3.3 · Maven multi-module · H2 (file) + Spring Data JPA + Flyway · JUnit 5 |
| Tests | 205 — 189 unit/contract plus 16 integration over real HTTP |
| Runtime dependencies | none — one JVM, one database file |

---

## Run it

Prerequisite: **JDK 21**. Nothing else — no Docker, no database to install, no network at runtime.

```bash
./mvnw verify                                    # build, 189 unit tests + 16 integration tests
java -jar app/target/agentic-url-shortener.jar   # starts on http://localhost:8080
```

On Windows use `mvnw.cmd`, and run the shell scripts from Git Bash. If `java -version` reports
anything below 21, put JDK 21 on the path for the session first — setting `JAVA_HOME` alone is not
enough, because it steers Maven but not a bare `java` command:

```powershell
$env:JAVA_HOME = "C:\path\to\jdk-21"
$env:PATH = "$env:JAVA_HOME\bin;" + $env:PATH
```

Leave that terminal running. Then, in a second one:

```bash
./scripts/demo-scenarios.sh                      # runs all three scenarios, writes the evidence
```

Interactive API documentation is at <http://localhost:8080/swagger-ui.html>. Click **Authorize**,
paste `demo-operator-key`, and every endpoint below is callable from the browser.

### Demo API keys

The prototype ships two keys. Only their SHA-256 hashes are in `application.yml`; the plaintext is
here because this is a local prototype, and is **not safe to keep** anywhere else — see
[SECURITY.md](SECURITY.md).

| Key | Scopes | Use |
|---|---|---|
| `demo-operator-key` | READ, WRITE, CONTROL | create links, start runs, decide gates |
| `demo-reviewer-key` | READ | inspect links, runs, audit and metrics |

The split is the point: a reviewer can read every run and every metric without being able to approve
anything.

---

## Command reference

Every command below was executed against the running system. Set these first so the rest pastes as-is:

```bash
BASE=http://localhost:8080
OP='X-Api-Key: demo-operator-key'
JSON='Content-Type: application/json'
```

### Health — no key required

```bash
curl -s $BASE/health/live      # {"status":"UP"} — is the process alive? if not, restart it
curl -s $BASE/health/ready     # adds database, analyticsQueue, analyticsQueueDepth, analyticsDropped
```

`live` and `ready` are deliberately different. A failing `ready` means stop sending traffic; it does
not mean restart, because a briefly unreachable database is not fixed by restarting. Both are
unauthenticated, because a load balancer cannot hold credentials. `analyticsDropped` is exposed so
the cost of the drop-clicks-rather-than-slow-redirects trade-off is visible instead of assumed.

### Application plane — the shortener

```bash
# Create a link. Returns 201 and the short code.
curl -s -X POST $BASE/api/v1/links -H "$OP" -H "$JSON" \
  -d '{"url":"https://www.wikipedia.org/wiki/Main_Page"}'

# Follow it. 302 while live, 410 once expired, 404 if it never existed.
curl -si $BASE/CODE | head -5

# Inspect the link without counting a click.
curl -s $BASE/api/v1/links/CODE -H "$OP"

# Click analytics. from/to are optional ISO-8601 bounds; the default window is the last 30 days.
curl -s $BASE/api/v1/links/CODE/stats -H "$OP"
curl -s "$BASE/api/v1/links/CODE/stats?from=2026-01-01T00:00:00Z&to=2026-12-31T23:59:59Z" -H "$OP"

# Delete it. Afterwards the code returns 404, not 410.
curl -s -o /dev/null -w '%{http_code}\n' -X DELETE $BASE/api/v1/links/CODE -H "$OP"
```

Replace `CODE` with the code the create call returned. Counts are eventually consistent
(`"consistency":"eventual (<=1s)"`), so read stats a moment after clicking — the redirect never waits
on the analytics write.

**Refused on purpose.** These are worth running, because the error is the feature:

```bash
# 422 URL_HOST_BLOCKED — the redirect endpoint cannot be turned into a way into the host network
curl -s -X POST $BASE/api/v1/links -H "$OP" -H "$JSON" -d '{"url":"https://localhost/admin"}'

# 400 URL_SCHEME_NOT_ALLOWED
curl -s -X POST $BASE/api/v1/links -H "$OP" -H "$JSON" -d '{"url":"javascript:alert(1)"}'

# 401 with no key, and 401 again for a read-only key attempting a write
curl -s -o /dev/null -w '%{http_code}\n' -X POST $BASE/api/v1/links -H "$JSON" \
  -d '{"url":"https://example.org"}'
curl -s -o /dev/null -w '%{http_code}\n' -X POST $BASE/api/v1/links -H "$JSON" \
  -H 'X-Api-Key: demo-reviewer-key' -d '{"url":"https://example.org"}'
```

**Idempotency.** The same key replays instead of minting a second code; the same key with a different
body is a conflict, not a silent overwrite:

```bash
K='Idempotency-Key: demo-001'
curl -s -o /dev/null -w '%{http_code}\n' -X POST $BASE/api/v1/links -H "$OP" -H "$JSON" -H "$K" \
  -d '{"url":"https://www.wikipedia.org"}'   # 201 created
curl -s -o /dev/null -w '%{http_code}\n' -X POST $BASE/api/v1/links -H "$OP" -H "$JSON" -H "$K" \
  -d '{"url":"https://www.wikipedia.org"}'   # 200 replay, same code
curl -s -o /dev/null -w '%{http_code}\n' -X POST $BASE/api/v1/links -H "$OP" -H "$JSON" -H "$K" \
  -d '{"url":"https://example.org"}'         # 409 conflict
```

### Control plane — the orchestration engine

```bash
# Start a run. waitMs blocks for up to that long, so you see a terminal or parked state directly.
curl -s -X POST "$BASE/api/v1/workflows?waitMs=30000" -H "$OP" -H "$JSON" -d '{
  "text":"Links should expire after a while and we should show popular links.",
  "requirementId":"REQ-SC-001","kind":"Unclassified","actor":"you"}'
```

That run **stops** at `AWAITING_CLARIFICATION` rather than guessing what "a while" means. Inspect it:

```bash
RUN=the_runId_from_above
curl -s $BASE/api/v1/workflows/$RUN           -H "$OP"   # state, nodes, attempts, ambiguities
curl -s $BASE/api/v1/workflows/$RUN/history   -H "$OP"   # the journal: every transition, timestamped
curl -s $BASE/api/v1/workflows/$RUN/artifacts -H "$OP"   # everything the run produced
curl -s $BASE/api/v1/workflows/$RUN/gates     -H "$OP"   # which gates exist and which are open
curl -s $BASE/api/v1/workflows/$RUN/graph     -H "$OP"   # Mermaid diagram, per-node state
```

`history` is where parallelism is provable: sibling nodes start within milliseconds of each other,
their execution windows overlap, and the join node starts only after the last of them finished.

Answer the gate. A blank rationale is rejected — an unexplained approval is indistinguishable from a
rubber stamp when someone reviews the run months later:

```bash
curl -s -X POST $BASE/api/v1/workflows/$RUN/gates/clarify/decision -H "$OP" -H "$JSON" -d '{
  "decision":"APPROVE","actor":"you",
  "rationale":"Default expiry is 90 days when expiresAt is omitted; popular means top 10 by clicks over the trailing 7 days, on an authenticated endpoint."}'
```

The definition version goes 1 → 2 and the nodes invalidated by that answer re-run. Work that did not
depend on it keeps its result, and its approval.

```bash
curl -s -X POST $BASE/api/v1/workflows/$RUN/resume    -H "$OP"   # continue after a safe stop
curl -s -X POST $BASE/api/v1/workflows/$RUN/safe-stop -H "$OP"   # park the run deliberately
```

A gate never advances on a timer. The approval timeout produces a safe stop, never an approval.

### Evidence

```bash
# Hash-chained audit log. The response headers say whether the chain verifies.
curl -si $BASE/api/v1/workflows/$RUN/audit -H "$OP" | grep -i '^X-Audit'
#   X-Audit-Chain: intact
#   X-Audit-Rows: 65

# Walk any artifact back to the sentence it came from, through the decisions in force.
curl -s $BASE/api/v1/workflows/$RUN/lineage/summary:RunSummary:v1 -H "$OP"

# The four required metrics, recomputed from the journal on every request.
curl -s $BASE/api/v1/metrics/reliability -H 'X-Api-Key: demo-reviewer-key'
```

Metrics read as zeros until runs exist — run `./scripts/demo-scenarios.sh` first. `mttrMs` is `null`
rather than `0` when nothing has been recovered, because no data and instant recovery are different
claims. Every response carries `dataClass: DEMONSTRATION` and lists the `runIds` it was computed from.

### Policy exceptions — the waiver workflow

Rules that cannot be satisfied report `EXCEPTION_REQUESTED`, which is neither a pass nor a violation:
it is a decision a human owes. This is where that decision gets recorded.

```bash
# Ask for a waiver. A request is not a waiver — note there is no approver and no expiry yet.
curl -s -X POST $BASE/api/v1/policy-exceptions -H "$OP" -H "$JSON" -d '{
  "policyId":"SEC-004",
  "reason":"No dependency vulnerability scanner is wired into this prototype.",
  "scope":"release candidate"}'

# Approve it. Both of these are refused:
#   compensatingControl ""  -> 400 "a waiver with no compensating control is just a gap"
#   validForDays 400        -> 400 "a longer waiver is a policy change, not an exception"
curl -s -X POST $BASE/api/v1/policy-exceptions/EXC_ID/decision -H "$OP" -H "$JSON" -d '{
  "decision":"APPROVE","approver":"you",
  "compensatingControl":"Manual review of the dependency tree before release.",
  "validForDays":30,"reviewCondition":"Revisit once a scanner is wired into the build."}'

# The standing register of every rule currently being bent, by whom, and until when.
curl -s $BASE/api/v1/policy-exceptions -H "$OP"
```

An approved exception expires — 90 days maximum, 30 by default — and the waived rule still reports
`EXCEPTION_REQUESTED` in the policy output. It never flips to green. The gap stays visible; what
changes is that someone has signed for it.

---

## Where to look

Start here, in this order:

| # | Document | What it gives you |
|---|---|---|
| 1 | [docs/assessment/requirements-coverage.md](docs/assessment/requirements-coverage.md) | The assignment clause by clause, against what exists |
| 2 | [docs/assessment/reviewer-guide.md](docs/assessment/reviewer-guide.md) | Every claim, with the command that proves it |
| 3 | [docs/assessment/demo-script.md](docs/assessment/demo-script.md) | A 20-minute walkthrough, with captured output |
| 4 | [docs/assessment/final-engineering-summary.md](docs/assessment/final-engineering-summary.md) | Plan, artifacts, risks, limitations, release decision |
| 5 | [docs/architecture/architecture.md](docs/architecture/architecture.md) | Components, orchestration model, control flow |
| 6 | [docs/scenarios/](docs/scenarios/) | The three executed scenarios and their evidence |
| 7 | [docs/adr/](docs/adr/) | The decisions, with the options that were rejected |
| 8 | [docs/traceability/matrix.md](docs/traceability/matrix.md) | Requirement → design → code → test |

---

## The two planes

```
                    ┌─────────────────────────────────────────────┐
   API consumer ───▶│  Application plane                          │
                    │  links · redirect · analytics · health      │
                    └─────────────────────────────────────────────┘
                                      │ (no dependency either way,
                                      │  enforced by ArchUnit)
                    ┌─────────────────────────────────────────────┐
   Engineer ───────▶│  Control plane                              │
   Approver         │  DAG engine · gates · policy · journal      │
                    └─────────────────────────────────────────────┘
                                      │
                    ┌─────────────────────────────────────────────┐
                    │  Telemetry: audit chain · reliability · MTTR │
                    └─────────────────────────────────────────────┘
```

Neither plane may depend on the other. That is not a convention — `ArchitectureBoundaryTest` fails
the build if it is ever violated.

### Application plane

`POST /api/v1/links` · `GET /{code}` · `GET /api/v1/links/{code}` ·
`GET /api/v1/links/{code}/stats` · `DELETE /api/v1/links/{code}` · `GET /health/live|ready`

URLs are validated and canonicalised before storage; hosts that resolve to loopback, link-local or
private addresses are refused at create time, so the redirect endpoint cannot be turned into a way
into the host network. Redirects are 302, not 301, so analytics are not defeated by caching. Clicks
are written off the redirect path through a bounded queue — counts are eventually consistent, and
under a flood the queue drops events rather than making redirects wait.

### Control plane

`POST /api/v1/workflows` · `GET /{runId}` · `/graph` · `/history` · `/audit` · `/artifacts` ·
`/lineage/{artifactId}` · `/gates` · `/gates/{gateId}/decision` · `/resume` · `/safe-stop` ·
`GET /api/v1/metrics/reliability` · `/api/v1/policy-exceptions`

The workflow is [a versioned JSON file](orchestration-core/src/main/resources/workflows/sdlc.v1.json)
you can read without reading the engine, validated against
[its published schema](specs/001-agentic-url-shortener/contracts/schemas/workflow-definition.schema.json)
and then as a DAG before anything runs.

Each node declares both gates. Its **entry** gate is its dependencies plus a branch condition; its
**exit** gate is the artifact it owes the run — a node that reports success without producing its
declared output has failed, not succeeded. Nodes run when their entry gate opens, which is what
produces parallelism, joins and skipped branches from a single rule. Retry, timeout and recovery
are owned by the engine, not by agents, so the journal's attempt count is the truth.

A gate never advances on a timer: the approval timeout produces a safe stop, and there is no code
path from elapsed time to an approved state.

---

## The three scenarios

Each was executed by the running system; the bundles under `docs/scenarios/` are captured responses,
not written examples.

| | Greenfield | Brownfield | Ambiguous |
|---|---|---|---|
| Requirement | optional `expiresAt` | base58 codes, legacy codes still resolve | "expire after a while", "popular links" |
| Clarification gate | skipped, with reasons | skipped | **triggered — run stops** |
| Impact gate | — | **required before any change** | — |
| Failure shown | transient → retry | permanent → rollback → resume | — |
| Governance artifact | release decision | impact approval + ADR-017 | clarification decision + definition v2 |
| Outcome | COMPLETED | COMPLETED | COMPLETED at definition v2 |

---

## What this is not

Stated plainly, because a prototype that overclaims is worse than one that is modest:

- **The agents are deterministic, not LLM-backed** (ASM-001). They are real executors behind a
  `StageAgent` interface, which is where an LLM adapter would go. That substitution is out of scope
  and has not been half-built.
- **The `implement` node records a change set; it does not write code** (EXC-004). Saying otherwise
  would be the one dishonesty this whole exercise exists to prevent.
- **The reliability numbers are labelled `DEMONSTRATION`** and come from a handful of scripted runs.
  They are not production statistics and the API says so in every response.
- **Single node, single process.** No multi-region, no horizontal scaling, no user accounts. See
  the exclusions in the [final summary](docs/assessment/final-engineering-summary.md).

Full limitations: [final engineering summary §19](docs/assessment/final-engineering-summary.md).
