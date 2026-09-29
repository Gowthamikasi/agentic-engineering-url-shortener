# Outcome: Scenario C - Ambiguous: 'expire after a while', 'popular links'

> Every figure below was read out of the captured bundle in this folder, which is the response the
> running system returned. Nothing here was written by hand.

| Field | Value |
|---|---|
| Run | `run_1a0ee3e2ee2_3` |
| Terminal state | **COMPLETED** |
| Terminal outcome | All nodes settled successfully |
| Definition version | 2 |
| Policy version | 1.0.0 |
| Policy result | `PASS:8 FAIL:0 EXC:3 NA:2` |
| Release blocked | False |
| Audit rows | 65 |
| Journal rows | 65 |

## Node states

| Node | State | Attempts | Reason |
|---|---|---:|---|
| `ingest` | SUCCEEDED | 1 | Attempt 1 |
| `normalize` | SUCCEEDED | 1 | Attempt 1 |
| `quality-check` | SUCCEEDED | 1 | Attempt 1 |
| `clarify` | SKIPPED | 0 | branchCondition false: #facts['quality.decision'] == 'ClarificationRequired' |
| `impact-analysis` | SKIPPED | 0 | branchCondition false: #input['kind'] == 'Brownfield' |
| `impact-approval` | SKIPPED | 0 | branchCondition false: #input['kind'] == 'Brownfield' |
| `decompose` | SUCCEEDED | 1 | Attempt 1 |
| `contract-design` | SUCCEEDED | 1 | Attempt 1 |
| `test-plan` | SUCCEEDED | 1 | Attempt 1 |
| `implement` | SUCCEEDED | 1 | Attempt 1 |
| `unit-tests` | SUCCEEDED | 1 | Attempt 1 |
| `contract-tests` | SUCCEEDED | 1 | Attempt 1 |
| `security-scan` | SUCCEEDED | 1 | Attempt 1 |
| `regression` | SKIPPED | 0 | branchCondition false: #input['kind'] == 'Brownfield' |
| `docs` | SUCCEEDED | 1 | Attempt 1 |
| `policy-eval` | SUCCEEDED | 1 | Attempt 1 |
| `release-gate` | SUCCEEDED | 0 | Gate approved |
| `summary` | SUCCEEDED | 1 | Attempt 1 |

## Governance and recovery events

| Seq | Action | Node | Detail |
|---|---|---|---|
| 12 | `ApprovalRequested` | `clarify` | Human decision required at gate clarify |
| 13 | `WorkflowAwaitingClarification` | `-` | Run parked at a human gate |
| 14 | `ApprovalDecided` | `clarify` | Default expiry is 90 days only when expiresAt is omitted; popular means the top  |
| 17 | `Invalidated` | `quality-check` | Upstream artifact of 'normalize' was superseded |
| 19 | `Invalidated` | `clarify` | Upstream artifact of 'normalize' was superseded |
| 21 | `Replanned` | `-` | Decision at gate clarify: Default expiry is 90 days only when expiresAt is omitt |
| 57 | `ApprovalRequested` | `release-gate` | Human decision required at gate release-gate |
| 58 | `WorkflowSuspended` | `-` | Run parked at a human gate |
| 59 | `ApprovalDecided` | `release-gate` | The clarified requirement is testable, the downstream subgraph was regenerated u |

## Human decisions

| Gate | Decision | Actor | Rationale |
|---|---|---|---|
| `clarify` | APPROVE | madhu | Default expiry is 90 days only when expiresAt is omitted; popular means the top 10 links by clicks over the tr |
| `release-gate` | READY | madhu | The clarified requirement is testable, the downstream subgraph was regenerated under definition v2, and all ma |

## Policy outcomes other than PASS

| Policy | Mandatory | Outcome | Reason |
|---|---|---|---|
| `SEC-004` | yes | EXCEPTION_REQUESTED | No dependency vulnerability scan was executed for this run. Awaiting a human decision |
| `LIC-001` | no | EXCEPTION_REQUESTED | No dependency licence report was produced for this run. Awaiting a human decision on  |
| `CC-002` | yes | NOT_APPLICABLE | This run changes no architecture decision. |
| `CC-003` | yes | NOT_APPLICABLE | Input is not classified as brownfield. |
| `TEST-001` | yes | EXCEPTION_REQUESTED | TDD was required for one or more tasks, but this run captured no red-then-green evide |

## Replanning

- v1 -> v2: invalidated [quality-check, clarify] because Decision at gate clarify: Default expiry is 90 days only when expiresAt is omitted; popular means the top 10 links by clicks over the trailing 7 days, exposed only on an authenticated endpoint; REQ-SA-001 is amended to allow a default.

## Files in this bundle

| File | Contents |
|---|---|
| `input.md` | the requirement as submitted, and what to watch |
| `run-run_1a0ee3e2ee2_3.json` | final snapshot from `GET /workflows/{id}` |
| `history.json` | the transition journal, in order |
| `audit.jsonl` | the hash-chained audit trail |
| `graph.mmd` | Mermaid graph coloured by final node state |
| `artifacts.json` | every artifact produced, with its provenance |
| `gates.json` | every gate and its decision |

## Reproduce

```bash
java -jar app/target/agentic-url-shortener.jar
./scripts/demo-scenarios.sh ambiguous
```

Run ids differ on every execution; the states, attempt counts and policy outcomes should not.
