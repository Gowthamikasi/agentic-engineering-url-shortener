# Demo script

A 20-minute walkthrough. Every output below was captured from a real run — nothing here is written
from memory.

**Before you start:** have the server running and Swagger open at
<http://localhost:8080/swagger-ui.html>, authorised with `demo-operator-key`.

```powershell
$env:JAVA_HOME = "C:\Users\Mohan\Downloads\jdk21\jdk21.0.12_12"
$env:PATH = "$env:JAVA_HOME\bin;" + $env:PATH
cd C:\Users\Mohan\Downloads\agentic-engineering-url-shortener
java -jar app\target\agentic-url-shortener.jar
```

---

## Opening line

> "The assignment says build a URL shortener. But the shortener is the demonstration domain — what's
> actually being assessed is an orchestration engine that takes a written requirement and drives it
> through the software lifecycle under human oversight. I'll show the shortener quickly, then spend
> most of the time on the engine."

---

# Act 1 — The application plane (5 minutes)

## 1. Create and follow a link

`POST /api/v1/links` with `{ "url": "https://www.wikipedia.org/wiki/Main_Page" }`

```json
{ "code": "ptD7xJx", "shortUrl": "http://localhost:8080/ptD7xJx",
  "target": "https://www.wikipedia.org/wiki/Main_Page" }
```

Open `localhost:8080/ptD7xJx` in a browser → Wikipedia loads.

> "302, not 301. A permanent redirect gets cached by browsers, and a click that never reaches the
> server can't be counted."

## 2. Analytics are off the hot path

`GET /api/v1/links/{code}/stats`

```json
{ "totalClicks": 1, "lastClickAt": "...", "daily": [{"date":"2026-09-29","clicks":1}],
  "consistency": "eventual (<=1s)" }
```

> "That `consistency` field is deliberate. Clicks are written by a background thread, so the redirect
> never waits on the database. Under load the queue drops events rather than slowing redirects —
> losing a count is the lesser harm. The API states the trade-off instead of hiding it."

## 3. Security: the redirect endpoint can't be weaponised

`POST /api/v1/links` with `{ "url": "https://localhost/admin" }`

```json
{ "status": 422, "errorCode": "URL_HOST_BLOCKED",
  "detail": "Host 'localhost' is an internal name and is not allowed as a redirect target." }
```

With `{ "url": "javascript:alert(1)" }`

```json
{ "status": 400, "errorCode": "URL_SCHEME_NOT_ALLOWED",
  "detail": "Scheme 'javascript' is not allowed; only http and https are accepted." }
```

> "Validation happens at create time, not redirect time. A host resolving to a private address never
> gets a short code, so there's nothing to follow later. This is the one endpoint serving anonymous
> traffic, so it's the one that has to be safe."

## 4. Status codes that mean something

| Case | Result |
|---|---|
| live link | `302` |
| expired link | `410 LINK_EXPIRED` |
| never existed | `404 LINK_NOT_FOUND` |

> "410 versus 404 matters. A caller can tell 'this expired' from 'this was never real' — which is the
> difference between a bug and a business rule when someone reports a broken link."

## 5. Idempotency

Same `Idempotency-Key` three times:

```
1st, same body      -> 201  (created)
2nd, same body      -> 200  (replay, same code)
3rd, different body -> 409  (conflict)
```

> "A retried request doesn't burn a second code. A reused key with different content is a conflict,
> not a silent overwrite."

## 6. Authentication and scopes

```
no key                    -> 401
read-only key, POST       -> 401
read-only key, GET        -> 200
```

> "Keys are stored as SHA-256 hashes and compared in constant time against every configured key, so
> timing doesn't reveal which one nearly matched. A reviewer can read a run without being able to
> approve it."

---

# Act 2 — The control plane (10 minutes) — **the centrepiece**

## 7. Hand it something vague

`POST /api/v1/workflows?waitMs=30000`

```json
{ "text": "Links should expire after a while and we should show popular links.",
  "requirementId": "REQ-SC-001", "kind": "Unclassified", "actor": "madhu" }
```

```json
{ "runId": "run_1a0eeb12f0d_1", "state": "AWAITING_CLARIFICATION" }
```

**Pause here.** This is the moment worth selling.

`GET /api/v1/workflows/{runId}`

```
quality-check   SUCCEEDED            attempts=1
clarify         AWAITING_APPROVAL    attempts=0
implement       PENDING              attempts=0     <-- never started

ambiguities found: 4
  - 'a while' is not quantified; no value, unit or window is given.
  - 'popular' is not quantified; no value, unit or window is given.
  - 'show' does not say through which surface, or who is allowed to see it.
  - Expiry is requested without a duration or an explicit timestamp field.
```

> "It read the requirement, found four things it couldn't implement safely, and stopped. `implement`
> has zero attempts — it wrote nothing. Most systems would guess. This one asks."

## 8. A timer cannot approve a gate

Wait, then re-check:

```
after waiting, still: AWAITING_CLARIFICATION
```

> "There is no code path from elapsed time to an approved state. The approval timeout leads to a safe
> stop, never an approval. That's enforced by the state machine — `AWAITING_APPROVAL` has no edge to
> `SUCCEEDED` — and there's a negative test for it."

## 9. An unexplained approval is refused

Post a decision with `"rationale": ""`:

```json
{ "status": 400, "detail": "rationale must be a real explanation, not a placeholder" }
```

> "An approval with no stated reason is indistinguishable from a rubber stamp when someone reviews
> the run months later. So it's required."

## 10. The human answers, and the graph reshapes itself

Post the real decision:

```json
{ "decision": "APPROVE", "actor": "madhu",
  "rationale": "Default expiry is 90 days when expiresAt is omitted; popular means top 10 by clicks over the trailing 7 days, on an authenticated endpoint." }
```

```
definitionVersion now: 2   (was 1)
replan: v1 -> v2: invalidated [quality-check, clarify] because Decision at gate clarify: ...
```

> "The answer superseded the normalised requirement, so everything downstream of it was invalidated
> and re-run under a new definition version. Work that didn't depend on the change kept its result —
> and its approval. That's replanning without throwing away governance."

## 11. Parallel execution and the join — from the journal

`GET /api/v1/workflows/{runId}/history`

```
unit-tests      NodeStarted    19:43:23.745
contract-tests  NodeStarted    19:43:23.751
security-scan   NodeStarted    19:43:23.803
docs            NodeStarted    19:43:23.817
security-scan   NodeSucceeded  19:43:23.830
docs            NodeSucceeded  19:43:23.847
unit-tests      NodeSucceeded  19:43:24.186
contract-tests  NodeSucceeded  19:43:24.200
policy-eval     NodeStarted    19:43:24.224   <-- after ALL of them
```

> "Four nodes started within 70 milliseconds of each other and their execution windows overlap —
> that's real parallelism, not a sequence. And `policy-eval` starts 24ms after the last one finished,
> never before. That's the join, and it's an ordering fact in the journal rather than a claim."

## 12. Policy guardrails — and honest gaps

```
policy: PASS:8 FAIL:0 EXC:3 NA:2 | releaseBlocked: false

SEC-004   EXCEPTION_REQUESTED   No dependency vulnerability scan was executed for this run.
LIC-001   EXCEPTION_REQUESTED   No dependency licence report was produced for this run.
TEST-001  EXCEPTION_REQUESTED   TDD was required but this run captured no red-then-green evidence.
CC-002    NOT_APPLICABLE        This run changes no architecture decision.
CC-003    NOT_APPLICABLE        Input is not classified as brownfield.
```

> "Three exception requests, and they're the interesting part. No scanner ran, so it says no scanner
> ran — it does not report a clean scan. A gap a human has to decide on is a different outcome from a
> proven violation, and different again from a rule that doesn't apply. Most systems would collapse
> all three into a green tick."

---

# Act 3 — Brownfield: reasoning and recovery (5 minutes)

## 13. It reads the actual codebase

Start a brownfield run with the base58 requirement. The impact analysis:

```
scanned 135 files -> 15 impacted across 6 modules
  DesignAgents.java              score 10
  Alphabet.java                  score 8
  application.yml                score 7
  ApplicationPlaneConfig.java    score 7
  UrlShortenerProperties.java    score 5

rollback plan: flag flip: no data is mutated, so the previous behaviour is one setting away
```

> "Those are real files you can open. It derives search terms from the requirement, scores every
> source file, then follows references — so `ShortCodeGenerator` is found because it *uses*
> `Alphabet`, not because of how the sentence was worded."

**Get ahead of the weakness:** `DesignAgents.java` outranks `Alphabet.java`. Say so first:

> "The top hit is wrong — that's the agent source that *discusses* alphabets, because the repository
> contains a description of itself. It's a term search that follows references, not semantic
> understanding, and the report publishes its scores and matched terms so you can see exactly why
> each file is listed. It's limitation 12 in the summary."

## 14. The full recovery chain

The `regression` node is failed deliberately:

```
NodeFailed           a legacy base62 code was rejected by the narrowed validator (fault-injected)
RollbackStarted      Reverting artifacts of regression
RollbackCompleted    Artifacts reverted to their prior version
SafeStop             Rolled back and awaiting a human decision
WorkflowResumed      Resumed
NodeSucceeded        14 of 14 test(s) passed in 2 suites

final state: COMPLETED | regression attempts: 2
```

> "Permanent failure, rollback, safe stop, human resume, success. Note it *stopped and waited* rather
> than retrying — retrying a permanent failure repeats whatever caused it. Whether a node is
> rollbackable or compensatable is declared in the definition; the engine never guesses, because
> getting that wrong means the audit trail records an undo that never happened."

---

# Act 4 — The evidence (3 minutes)

## 15. Tamper-evident audit trail

`GET /api/v1/workflows/{runId}/audit`

```
X-Audit-Chain: intact
X-Audit-Rows: 65
```

> "Every row commits to the previous row's hash. Edit or delete one and every hash after it breaks,
> and the header names the row where it fails. Tamper-evident, not tamper-proof — someone with
> database access could recompute the chain, which is stated in the threat model."

## 16. Decision lineage

`GET /api/v1/workflows/{runId}/lineage/summary:RunSummary:v1`

```
13 steps from the summary back to the raw requirement:
  summary:RunSummary:v1
  policy-eval:PolicyEvaluation:v1
  unit-tests:TestReport:v1
  contract-tests:TestReport:v1
  ...
  ingest:RawRequirement:v1

decisions in force: ['clarify', 'release-gate']
```

> "Pick any artifact and walk it back to the sentence it came from, with the decisions that were in
> force at each step. It survives the human gates too — a gate produces no artifact, so the chain
> looks through it rather than stopping there."

## 17. Reliability metrics

`GET /api/v1/metrics/reliability`

```
dataClass: DEMONSTRATION
runs: 2 | successRate: 1.0
attempts: 30 | retries: 0 | rollbacks: 1
MTTR: 920 ms over 1 recovered | 0 unrecovered
  regression recovered by Rollback in 920 ms
latency p50/p95: 26877 / 34129 ms
```

> "All four metrics the assignment names, computed from the journal on every request — there's
> nowhere to type a number in. MTTR averages recovered failures only; unrecovered ones are counted
> separately, because folding them together would make MTTR look best exactly when things went worst.
> And every response is labelled DEMONSTRATION — these are scripted runs, not production statistics."

## 18. It survives a restart

Stop the process. Start it again. Request the same run:

```
/{runId}     200      /artifacts  200 (13 artifacts)
/audit       200      /graph      200
/history     200      /gates      200 (release-gate SUCCEEDED, READY)
lineage      13 steps -> ingest:RawRequirement:v1
metrics      runs 3, rate 1.0, MTTR 752 ms
```

> "Nothing is only in memory. A finished run is rebuilt from the database the first time it's asked
> for. This one was actually a bug an external reviewer found — the API used to read from a live map
> and lost everything on restart while the docs claimed otherwise. It's fixed, and both the fix and
> the original mistake are written up in the summary."

---

# Closing

> "205 tests, 12 modules, no Docker, one JDK. Three scenarios that behave materially differently, and
> the evidence under `docs/scenarios/` is captured API output, not written examples.
>
> What it deliberately does not do: the agents are deterministic rather than LLM-backed, the
> `implement` node records a change set rather than writing code, and no vulnerability scanner runs.
> All three are disclosed in the artifact that would otherwise imply the opposite — because the whole
> argument of the project is that a gap is never reported as a pass."

---

# Questions you'll be asked

**"Are these real AI agents?"**
> "No, and that's deliberate. They're deterministic executors behind a `StageAgent` interface, which
> is exactly where an LLM adapter would go. Deterministic means every test repeats and every piece of
> evidence in the repo is reproducible. An LLM in there would make the orchestration behaviour — which
> is what's being assessed — impossible to test."

**"Does `implement` actually write code?"**
> "No. It records a rollbackable change set, and the artifact it produces says so in its own text.
> Claiming otherwise would be the one dishonesty this project exists to prevent."

**"How do I know the parallelism is real?"**
> "Timestamps in the journal — four nodes with overlapping execution windows. And there's a test that
> asserts each sibling started before the other finished, not just that both ran."

**"What if I don't trust the numbers?"**
> "Delete `data/`, run `./scripts/demo-scenarios.sh`, and compare. Counts — attempts, retries,
> rollbacks, audit rows — come out identical every time. Millisecond timings won't, and the summary
> says so rather than quoting them as constants."

**"What would you do next?"**
> "Rehydrate interrupted runs, not just finished ones. Add a real vulnerability scanner so SEC-004
> can pass honestly. And replace the term-search impact analysis with something that parses the
> syntax tree, so it stops ranking a file that discusses alphabets above the one that defines them."
