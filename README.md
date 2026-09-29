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
| Tests | 157 across 8 modules, all passing |
| Runtime dependencies | none — one JVM, one database file |

---

## Run it

Prerequisite: **JDK 21**. Nothing else — no Docker, no database to install, no network at runtime.

```bash
./mvnw verify                                    # build and run all 157 tests
java -jar app/target/agentic-url-shortener.jar   # starts on http://localhost:8080
```

On Windows use `mvnw.cmd`, and run the shell scripts from Git Bash.

Then, in a second terminal:

```bash
./scripts/demo-scenarios.sh                      # runs all three scenarios, writes the evidence
```

Interactive API documentation is at <http://localhost:8080/swagger-ui.html>.

### Demo API keys

The prototype ships two keys. Only their SHA-256 hashes are in `application.yml`; the plaintext is
here because this is a local prototype, and is **not safe to keep** anywhere else — see
[SECURITY.md](SECURITY.md).

| Key | Scopes | Use |
|---|---|---|
| `demo-operator-key` | READ, WRITE, CONTROL | create links, start runs, decide gates |
| `demo-reviewer-key` | READ | inspect links, runs, audit and metrics |

---

## Try it in 60 seconds

```bash
# Application plane: create a link and follow it
curl -X POST http://localhost:8080/api/v1/links \
  -H 'X-Api-Key: demo-operator-key' -H 'Content-Type: application/json' \
  -d '{"url":"https://example.org/docs","expiresAt":"2030-12-31T23:59:59Z"}'

curl -i http://localhost:8080/<code>            # 302 to the target; 410 once it has expired

# Control plane: start a governed run and watch where it stops
curl -X POST 'http://localhost:8080/api/v1/workflows?waitMs=30000' \
  -H 'X-Api-Key: demo-operator-key' -H 'Content-Type: application/json' \
  -d '{"text":"Links should expire after a while and we should show popular links.",
       "requirementId":"REQ-SC-001","kind":"Unclassified","actor":"you"}'
```

That last run **stops**. It does not guess what "a while" means — it parks at a clarification gate
and waits for a human. That behaviour, not the shortener, is the point of this repository.

---

## Where to look

Start here, in this order:

| # | Document | What it gives you |
|---|---|---|
| 1 | [docs/assessment/reviewer-guide.md](docs/assessment/reviewer-guide.md) | Every claim, with the command that proves it |
| 2 | [docs/assessment/final-engineering-summary.md](docs/assessment/final-engineering-summary.md) | Plan, artifacts, risks, limitations, release decision |
| 3 | [docs/architecture/architecture.md](docs/architecture/architecture.md) | Components, orchestration model, control flow |
| 4 | [docs/scenarios/](docs/scenarios/) | The three executed scenarios and their evidence |
| 5 | [docs/adr/](docs/adr/) | The decisions, with the options that were rejected |
| 6 | [docs/traceability/matrix.md](docs/traceability/matrix.md) | Requirement → design → code → test |

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
you can read without reading the engine. Nodes run when their dependencies are satisfied, which is
what produces parallelism, joins and skipped branches from one rule. Retry, timeout and recovery are
owned by the engine, not by agents, so the journal's attempt count is the truth.

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
