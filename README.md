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

# Demo walkthrough — from a clean machine

Follow this top to bottom on a machine that has never seen the project. Every command was executed
against a fresh clone; the outputs shown are what came back.

## Step 0 — what the machine needs

**JDK 21 and git. That is all.** No Maven (a wrapper is included), no Docker, no database to
install, no network once it is built.

```bash
java -version
```

If that reports anything below 21, put a JDK 21 on the path **for this terminal session**. Setting
`JAVA_HOME` alone is not enough — it steers Maven, but not a bare `java` command:

```powershell
$env:JAVA_HOME = "C:\path\to\jdk-21"
$env:PATH = "$env:JAVA_HOME\bin;" + $env:PATH
java -version
```

A portable JDK 21 unzipped into any folder works; it does not need installing or admin rights.

## Step 1 — clone somewhere with a short path

```bash
cd C:\
mkdir demo
cd demo
git clone https://github.com/Gowthamikasi/agentic-engineering-url-shortener.git
cd agentic-engineering-url-shortener
```

> **Windows path limit — this bites.** The project has deep Java package directories. Cloning into
> an already-deep folder fails part way with `Filename too long`, and git leaves you a **partial
> checkout that still looks like it worked**. Clone somewhere short such as `C:\demo`, or enable
> long paths first:
>
> ```bash
> git config --global core.longpaths true
> ```
>
> To confirm the checkout is complete, `git status` must print nothing. If it lists deleted files,
> the clone failed — delete the folder and clone again somewhere shorter.

## Step 2 — build, and let every test run

```bash
./mvnw verify
```

On Windows: `.\mvnw.cmd verify`. First run downloads dependencies, so give it a few minutes and a
network connection. Expect `BUILD SUCCESS` and 205 tests.

> **Do not use `-DskipTests` before a demo.** The `unit-tests` and `contract-tests` agents read the
> **real** Surefire reports this build produces. Skip the tests and the workflow run will correctly
> refuse to proceed — `No test reports matched 'contract-tests'. Run the build first; absence of
> evidence is not a pass` — then roll back and safe-stop. That is the system working as designed,
> but it is not the demo you want. A red build produces the same honest refusal.

## Step 3 — start it

```bash
java -jar app/target/agentic-url-shortener.jar
```

Wait for `Started Application in ... seconds`. **Leave this terminal open** — closing it stops the
server. Run the jar from the same folder each time: the database is written to `./data` relative to
your working directory, so a different folder means a different, empty database.

Open <http://localhost:8080/swagger-ui.html>, click **Authorize**, paste `demo-operator-key`, and
every endpoint becomes callable from the browser. The whole demo can be driven from that page — the
`curl` commands below are the same calls if you prefer a terminal.

## Step 4 — warm up the data before anyone is watching

A fresh clone has no database, so the metrics endpoint honestly returns zeros and `mttrMs: null`.
Correct behaviour; poor opening slide. In a **second terminal**, populate it:

```bash
./scripts/demo-scenarios.sh
```

That needs Git Bash. If it is not available, just run the workflow in step 8 twice from Swagger —
that alone gives you runs, artifacts, an audit chain, lineage and real metrics.

Do this ten minutes early, not live.

---

## Demo API keys

| Key | Scopes | Use |
|---|---|---|
| `demo-operator-key` | READ, WRITE, CONTROL | create links, start runs, decide gates |
| `demo-reviewer-key` | READ | inspect links, runs, audit and metrics |

The split is the point: a reviewer can read every run and every metric without being able to approve
anything. Only SHA-256 hashes are in `application.yml`; the plaintext is here because this is a local
prototype and is **not safe to keep** anywhere else — see [SECURITY.md](SECURITY.md).

Set these once in your command terminal and the rest pastes as-is:

```bash
BASE=http://localhost:8080
OP='X-Api-Key: demo-operator-key'
JSON='Content-Type: application/json'
```

---

## Step 5 — health

```bash
curl -s $BASE/health/live
curl -s $BASE/health/ready
```

```json
{"status":"UP"}
{"status":"UP","database":"UP","analyticsQueue":"UP","analyticsQueueDepth":0,"analyticsDropped":0}
```

Two checks because they answer different questions. Failing `live` means restart me; failing `ready`
means stop sending me traffic but do **not** restart me, because a briefly unreachable database is
not fixed by restarting. Both are unauthenticated, since a load balancer cannot hold credentials.
`analyticsDropped` publishes the cost of the drop-clicks-rather-than-slow-redirects trade-off instead
of leaving it to be discovered.

## Step 6 — the shortener

```bash
curl -s -X POST $BASE/api/v1/links -H "$OP" -H "$JSON" \
  -d '{"url":"https://www.wikipedia.org/wiki/Main_Page"}'
```

```json
{"code":"hhXBoMK","shortUrl":"http://localhost:8080/hhXBoMK",
 "target":"https://www.wikipedia.org/wiki/Main_Page","createdAt":"2026-09-29T20:45:33Z"}
```

Take the `code` from that response and use it below — replace `CODE` with it. Pasting the word
`CODE` literally is a 404.

```bash
curl -si $BASE/CODE | head -5                          # 302 while live, 410 expired, 404 never existed
curl -s  $BASE/api/v1/links/CODE -H "$OP"              # inspect without counting a click
curl -s  $BASE/api/v1/links/CODE/stats -H "$OP"        # click analytics
curl -s -o /dev/null -w '%{http_code}\n' -X DELETE $BASE/api/v1/links/CODE -H "$OP"
```

Or paste `http://localhost:8080/CODE` into a browser and watch Wikipedia load.

```json
{"code":"hhXBoMK","totalClicks":1,"lastClickAt":"2026-09-29T20:45:34Z",
 "daily":[{"date":"2026-09-29","clicks":1}],"consistency":"eventual (<=1s)"}
```

Counts are eventually consistent, so read stats a second after clicking — the redirect never waits on
the analytics write. The count only moves when a request actually reaches the server: pressing Back,
or returning to a tab already on Wikipedia, does not reach it. `stats` also takes optional ISO-8601
`from` and `to` bounds; the default window is the last 30 days.

**Refused on purpose.** Run these — the error is the feature:

```bash
# 422 — the redirect endpoint cannot be turned into a way into the host network
curl -s -X POST $BASE/api/v1/links -H "$OP" -H "$JSON" -d '{"url":"https://localhost/admin"}'

# 400 — only http and https are accepted
curl -s -X POST $BASE/api/v1/links -H "$OP" -H "$JSON" -d '{"url":"javascript:alert(1)"}'

# 401 with no key, 401 again for a read-only key attempting a write
curl -s -o /dev/null -w '%{http_code}\n' -X POST $BASE/api/v1/links -H "$JSON" \
  -d '{"url":"https://example.org"}'
curl -s -o /dev/null -w '%{http_code}\n' -X POST $BASE/api/v1/links -H "$JSON" \
  -H 'X-Api-Key: demo-reviewer-key' -d '{"url":"https://example.org"}'
```

```json
{"status":422,"errorCode":"URL_HOST_BLOCKED",
 "detail":"Host 'localhost' is an internal name and is not allowed as a redirect target."}
{"status":400,"errorCode":"URL_SCHEME_NOT_ALLOWED",
 "detail":"Scheme 'javascript' is not allowed; only http and https are accepted."}
```

## Step 7 — idempotency

```bash
K='Idempotency-Key: demo-001'
curl -s -o /dev/null -w '%{http_code}\n' -X POST $BASE/api/v1/links -H "$OP" -H "$JSON" -H "$K" \
  -d '{"url":"https://www.wikipedia.org"}'   # 201 created
curl -s -o /dev/null -w '%{http_code}\n' -X POST $BASE/api/v1/links -H "$OP" -H "$JSON" -H "$K" \
  -d '{"url":"https://www.wikipedia.org"}'   # 200 replay, same code
curl -s -o /dev/null -w '%{http_code}\n' -X POST $BASE/api/v1/links -H "$OP" -H "$JSON" -H "$K" \
  -d '{"url":"https://example.org"}'         # 409 conflict
```

A retried request does not burn a second code. A reused key with different content is a conflict, not
a silent overwrite.

## Step 8 — the control plane: hand it something vague

```bash
curl -s -X POST "$BASE/api/v1/workflows?waitMs=30000" -H "$OP" -H "$JSON" -d '{
  "text":"Links should expire after a while and we should show popular links.",
  "requirementId":"REQ-SC-001","kind":"Unclassified","actor":"you"}'
```

```json
{"runId":"run_1a0eeeaa263_1","state":"AWAITING_CLARIFICATION"}
```

**Pause here.** It read the requirement, found four things it could not implement safely, and
stopped. `implement` has zero attempts — it wrote nothing.

Save the run id, then inspect:

```bash
RUN=run_1a0eeeaa263_1        # use the runId you got back
curl -s $BASE/api/v1/workflows/$RUN           -H "$OP"   # state, nodes, attempts, ambiguities
curl -s $BASE/api/v1/workflows/$RUN/history   -H "$OP"   # every transition, timestamped
curl -s $BASE/api/v1/workflows/$RUN/artifacts -H "$OP"   # everything the run produced
curl -s $BASE/api/v1/workflows/$RUN/gates     -H "$OP"   # which gates exist and their state
curl -s $BASE/api/v1/workflows/$RUN/graph     -H "$OP"   # Mermaid diagram, per-node state
```

`history` is where parallelism is provable: sibling nodes start within milliseconds of each other,
their execution windows overlap, and the join node starts only after the last of them finished.

## Step 9 — answer the gate

An unexplained approval is indistinguishable from a rubber stamp months later, so a blank rationale
is refused:

```bash
curl -s -X POST $BASE/api/v1/workflows/$RUN/gates/clarify/decision -H "$OP" -H "$JSON" \
  -d '{"decision":"APPROVE","actor":"you","rationale":""}'
```

```json
{"status":400,"detail":"rationale: rationale must be a real explanation, not a placeholder"}
```

Now answer it properly:

```bash
curl -s -X POST $BASE/api/v1/workflows/$RUN/gates/clarify/decision -H "$OP" -H "$JSON" -d '{
  "decision":"APPROVE","actor":"you",
  "rationale":"Default expiry is 90 days when expiresAt is omitted; popular means top 10 by clicks over the trailing 7 days, on an authenticated endpoint."}'
```

The definition version goes **1 → 2** and the nodes invalidated by that answer re-run. Work that did
not depend on it keeps its result, and its approval.

## Step 10 — the second gate, which every run has

Waiting will not advance anything: the approval timeout produces a safe stop, never an approval.
There is no code path from elapsed time to an approved state.

Check what is still open — the run is not finished until `release-gate` is answered too:

```bash
curl -s $BASE/api/v1/workflows/$RUN/gates -H "$OP"
```

```
clarify          -> SKIPPED             (answered, then superseded by the v2 replan)
impact-approval  -> SKIPPED             (only applies to Brownfield input)
release-gate     -> AWAITING_APPROVAL
```

```bash
curl -s -X POST $BASE/api/v1/workflows/$RUN/gates/release-gate/decision -H "$OP" -H "$JSON" -d '{
  "decision":"APPROVE","actor":"you",
  "rationale":"Tests pass, policy reports three known exceptions with compensating controls, and the limitations are disclosed in the run summary."}'
```

Two more controls exist for when a run stops on its own:

```bash
curl -s -X POST $BASE/api/v1/workflows/$RUN/resume    -H "$OP"   # continue after a safe stop
curl -s -X POST $BASE/api/v1/workflows/$RUN/safe-stop -H "$OP"   # park the run deliberately
```

## Step 11 — the evidence

```bash
curl -si $BASE/api/v1/workflows/$RUN/audit -H "$OP" | grep -i '^X-Audit'
```

```
X-Audit-Chain: intact
X-Audit-Rows: 62
```

Every row commits to the previous row's hash. Edit or delete one and every hash after it breaks, and
the header names the row where it fails. Tamper-evident, not tamper-proof.

```bash
curl -s $BASE/api/v1/workflows/$RUN/lineage/summary:RunSummary:v1 -H "$OP"
```

Walks any artifact back to the sentence it came from, through the decisions in force at each step.
It survives human gates: a gate produces no artifact, so the chain looks through it rather than
stopping there. **This needs a completed run** — ask for an artifact the run never produced and you
get an honest 404, so list `/artifacts` first and pick one from there.

```bash
curl -s $BASE/api/v1/metrics/reliability -H 'X-Api-Key: demo-reviewer-key'
```

All four required metrics, recomputed from the journal on every request — there is nowhere to type a
number in. `mttrMs` is `null` rather than `0` when nothing has been recovered, because no data and
instant recovery are different claims. Every response carries `dataClass: DEMONSTRATION` and lists
the `runIds` it was computed from.

## Step 12 — the waiver workflow

Rules that cannot be satisfied report `EXCEPTION_REQUESTED` — neither a pass nor a violation, but a
decision a human owes. This is where it gets recorded.

```bash
curl -s -X POST $BASE/api/v1/policy-exceptions -H "$OP" -H "$JSON" -d '{
  "policyId":"SEC-004",
  "reason":"No dependency vulnerability scanner is wired into this prototype.",
  "scope":"release candidate"}'
```

```json
{"id":"exc_1a0eeccd57d","policyId":"SEC-004","reason":"...","scope":"release candidate"}
```

No approver, no expiry — a request is not a waiver. Take that `id` and try to approve it badly:

```bash
# 400 "a waiver with no compensating control is just a gap"
curl -s -X POST $BASE/api/v1/policy-exceptions/EXC_ID/decision -H "$OP" -H "$JSON" \
  -d '{"decision":"APPROVE","approver":"you","compensatingControl":""}'

# 400 "a longer waiver is a policy change, not an exception"
curl -s -X POST $BASE/api/v1/policy-exceptions/EXC_ID/decision -H "$OP" -H "$JSON" -d '{
  "decision":"APPROVE","approver":"you","compensatingControl":"Manual dependency review.",
  "validForDays":400}'
```

Then properly, and list the register:

```bash
curl -s -X POST $BASE/api/v1/policy-exceptions/EXC_ID/decision -H "$OP" -H "$JSON" -d '{
  "decision":"APPROVE","approver":"you",
  "compensatingControl":"Manual review of the dependency tree before release.",
  "validForDays":30,"reviewCondition":"Revisit once a scanner is wired into the build."}'

curl -s $BASE/api/v1/policy-exceptions -H "$OP"
```

An approved exception expires — 90 days maximum, 30 by default — and the waived rule still reports
`EXCEPTION_REQUESTED` in the policy output. It never flips to green. The gap stays visible; what
changes is that someone has signed for it.

## Step 13 — it survives a restart

Stop the server with `Ctrl+C`, start it again, and ask for the same run id. Every endpoint still
answers: artifacts, audit chain, lineage, decisions. Nothing lived only in memory — a finished run is
rebuilt from the database the first time it is asked for.

---

## If something goes wrong

| Symptom | Cause | Fix |
|---|---|---|
| `Filename too long` during clone | Windows 260-character path limit | Clone into a short path such as `C:\demo`, or `git config --global core.longpaths true`. Re-clone; a partial checkout still looks fine until `git status` shows deletions |
| `UnsupportedClassVersionError` | a bare `java` is older than 21 | Put JDK 21 on `PATH`, not just `JAVA_HOME` |
| Swagger says **Failed to fetch** with no status code | the server is not running | Restart the jar, reload the page, Authorize again |
| A run rolls back at `unit-tests` or `contract-tests` | the build was skipped or red | Run `./mvnw verify` to green, then start a new run |
| Metrics are all zeros, `mttrMs: null` | no runs exist yet | Run `./scripts/demo-scenarios.sh`, or start a workflow |
| Lineage returns 404 | that artifact was never produced | List `/artifacts` and pick one from it |
| A 404 on a short link you just made | `CODE` pasted literally | Substitute the real code from the create response |

---

## Where to look

| # | Document | What it gives you |
|---|---|---|
| 1 | [docs/assessment/requirements-coverage.md](docs/assessment/requirements-coverage.md) | The assignment clause by clause, against what exists |
| 2 | [docs/assessment/reviewer-guide.md](docs/assessment/reviewer-guide.md) | Every claim, with the command that proves it |
| 3 | [docs/assessment/demo-script.md](docs/assessment/demo-script.md) | A 20-minute spoken walkthrough, with captured output |
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
