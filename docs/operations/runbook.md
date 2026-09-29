# Runbook

Operating the prototype, and what to do when it misbehaves.

## Start and stop

```bash
./mvnw verify                                       # build and test
java -jar app/target/agentic-url-shortener.jar      # start on :8080
```

Stop with Ctrl-C. The click writer drains what it is holding on shutdown; whatever is still queued
is lost, which is the documented trade in ADR-016.

State lives in `./data/urlshortener.mv.db`. Deleting it resets everything — links, runs, journal and
audit trail alike. There is no separate reset command, deliberately: an operation that erases an
audit trail should look like deleting a file, not like a supported feature.

## Health

| Probe | Question it answers | Fails when |
|---|---|---|
| `GET /health/live` | Should this process be restarted? | the JVM is gone |
| `GET /health/ready` | Should this instance get traffic? | the database is unreachable, or the click queue is saturated |

A saturated queue means clicks are being dropped. That is a reason to shed traffic and not a reason
to restart: restarting would discard the queue as well.

## Configuration

Override in `application-local.yml` (git-ignored) or through the environment.

| Setting | Default | Effect |
|---|---|---|
| `urlshortener.short-code.alphabet` | `BASE62` | Alphabet for **new** codes. Lookup always accepts both — this is the ADR-017 flag |
| `urlshortener.rate-limit.enabled` | `true` | Rate limiting on or off |
| `urlshortener.validation.enforce-address-checks` | `true` | **Leave on.** Off, private hosts become valid redirect targets |
| `orchestration.max-parallelism` | `4` | Concurrent node attempts |
| `orchestration.approval-timeout` | `24h` | How long a gate waits before safe-stopping |
| `orchestration.project-root` | `.` | Where the test-runner agent looks for Surefire reports |

## Symptoms

### A run is not progressing

```bash
curl -H "X-Api-Key: demo-operator-key" localhost:8080/api/v1/workflows/<runId> | jq '.state, .nodes'
```

| State | What it means | What to do |
|---|---|---|
| `SUSPENDED` / `AWAITING_CLARIFICATION` | parked at a gate | decide it — this is the system working |
| `SAFE_STOPPED` | a permanent failure, exhausted retries, or an elapsed approval window | read the `SafeStop` reason, then `POST /resume` |
| `RUNNING` with everything settled | a scheduler bug | check the logs for `Engine failure on run` |

Check `waitingOn` on a pending node: it names exactly which dependencies it is still waiting for.

### A gate will not accept a decision

`409` means the gate is not awaiting one — read `/gates` for its actual state. `400` means the
decision was missing an actor or a rationale; both are required and blank values are refused.

### A node fails with "No test reports matched"

The `TestRunnerAgent` reads the build's real Surefire and Failsafe XML. If the build has not run,
there are no reports, and the node fails rather than reporting a pass it did not observe. Run
`./mvnw verify` first.

### The audit chain reports broken

```bash
curl -i -H "X-Api-Key: demo-operator-key" localhost:8080/api/v1/workflows/<runId>/audit | grep X-Audit-Chain
```

`broken-at-row-N` means row N no longer matches its own hash, or does not follow its predecessor.
Rows are only ever appended by the application, so a break means either direct database access or
data corruption. Treat the run's governance record as untrustworthy from row N onward and
investigate how the database was reached.

### Redirects are slow

Redirects do not touch the click writer synchronously, so look at the database first. Check
`/health/ready` for `analyticsQueueDepth` — a deep queue signals a slow writer, not a slow redirect.

## Backups

The whole state is one H2 file. Copy it while the process is stopped. There is no incremental
backup and no point-in-time recovery; for a prototype this is adequate and it is stated rather than
implied.

## What to check after a restart

Everything a finished run recorded is in the database, and the API rebuilds the run from there the
first time it is asked for. After a restart:

- **Finished runs answer on every endpoint** — `/{runId}`, `/history`, `/audit`, `/artifacts`,
  `/graph`, `/gates` and `/lineage`. Node states come from the journal, artifacts and facts from
  their own tables, decisions from the approval store.
- **Reliability metrics cover every run ever recorded**, because they are computed from the stored
  run headers and journal rather than from whatever is in memory.
- **Runs that were mid-flight are not resumed.** Their journal is intact and readable, but nothing
  picks the work back up; start them again.
