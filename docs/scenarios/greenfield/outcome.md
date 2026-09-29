# Outcome: Scenario A - Greenfield: optional link expiration

> Every figure below was read out of the captured bundle in this folder, which is the response the
> running system returned. Nothing here was written by hand.

| Field | Value |
|---|---|
| Run | `run_1a0ed63044f_1` |
| Terminal state | **COMPLETED** |
| Terminal outcome | All nodes settled successfully |
| Definition version | 1 |
| Policy version | 1.0.0 |
| Policy result | `PASS:10 FAIL:0 EXC:1 NA:2` |
| Release blocked | False |
| Audit rows | 55 |
| Journal rows | 55 |

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
| `contract-tests` | SUCCEEDED | 2 | H2 LOCK_TIMEOUT while reading the report (fault-injected) |
| `security-scan` | SUCCEEDED | 1 | Attempt 1 |
| `regression` | SKIPPED | 0 | branchCondition false: #input['kind'] == 'Brownfield' |
| `docs` | SUCCEEDED | 1 | Attempt 1 |
| `policy-eval` | SUCCEEDED | 1 | Attempt 1 |
| `release-gate` | SUCCEEDED | 0 | Gate approved |
| `summary` | SUCCEEDED | 1 | Attempt 1 |

## Governance and recovery events

| Seq | Action | Node | Detail |
|---|---|---|---|
| 32 | `NodeFailed` | `contract-tests` | H2 LOCK_TIMEOUT while reading the report (fault-injected) |
| 36 | `NodeRetryScheduled` | `contract-tests` | H2 LOCK_TIMEOUT while reading the report (fault-injected) |
| 47 | `ApprovalRequested` | `release-gate` | Human decision required at gate release-gate |
| 48 | `WorkflowSuspended` | `-` | Run parked at a human gate |
| 49 | `ApprovalDecided` | `release-gate` | All mandatory policies pass, the contract change is additive and version-bumped, |

## Human decisions

| Gate | Decision | Actor | Rationale |
|---|---|---|---|
| `release-gate` | READY | madhu | All mandatory policies pass, the contract change is additive and version-bumped, and the injected transient fa |

## Policy outcomes other than PASS

| Policy | Mandatory | Outcome | Reason |
|---|---|---|---|
| `LIC-001` | no | EXCEPTION_REQUESTED | No dependency licence report was produced for this run. Awaiting a human decision on  |
| `CC-002` | yes | NOT_APPLICABLE | This run changes no architecture decision. |
| `CC-003` | yes | NOT_APPLICABLE | Input is not classified as brownfield. |

## Replanning

_No replanning occurred in this run._

## Files in this bundle

| File | Contents |
|---|---|
| `input.md` | the requirement as submitted, and what to watch |
| `run-run_1a0ed63044f_1.json` | final snapshot from `GET /workflows/{id}` |
| `history.json` | the transition journal, in order |
| `audit.jsonl` | the hash-chained audit trail |
| `graph.mmd` | Mermaid graph coloured by final node state |
| `artifacts.json` | every artifact produced, with its provenance |
| `gates.json` | every gate and its decision |

## Reproduce

```bash
java -jar app/target/agentic-url-shortener.jar
./scripts/demo-scenarios.sh greenfield
```

Run ids differ on every execution; the states, attempt counts and policy outcomes should not.
