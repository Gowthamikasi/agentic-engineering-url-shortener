# Outcome: Scenario B - Brownfield: base58 codes, legacy codes still resolve

> Every figure below was read out of the captured bundle in this folder, which is the response the
> running system returned. Nothing here was written by hand.

| Field | Value |
|---|---|
| Run | `run_1a0ed631050_2` |
| Terminal state | **COMPLETED** |
| Terminal outcome | All nodes settled successfully |
| Definition version | 1 |
| Policy version | 1.0.0 |
| Policy result | `PASS:11 FAIL:0 EXC:1 NA:1` |
| Release blocked | False |
| Audit rows | 75 |
| Journal rows | 75 |

## Node states

| Node | State | Attempts | Reason |
|---|---|---:|---|
| `ingest` | SUCCEEDED | 1 | Attempt 1 |
| `normalize` | SUCCEEDED | 1 | Attempt 1 |
| `quality-check` | SUCCEEDED | 1 | Attempt 1 |
| `clarify` | SKIPPED | 0 | branchCondition false: #facts['quality.decision'] == 'ClarificationRequired' |
| `impact-analysis` | SUCCEEDED | 1 | Attempt 1 |
| `impact-approval` | SUCCEEDED | 0 | Gate approved |
| `decompose` | SUCCEEDED | 1 | Attempt 1 |
| `contract-design` | SUCCEEDED | 1 | Attempt 1 |
| `test-plan` | SUCCEEDED | 1 | Attempt 1 |
| `implement` | SUCCEEDED | 1 | Attempt 1 |
| `unit-tests` | SUCCEEDED | 1 | Attempt 1 |
| `contract-tests` | SUCCEEDED | 1 | Attempt 1 |
| `security-scan` | SUCCEEDED | 1 | Attempt 1 |
| `regression` | SUCCEEDED | 2 | a legacy base62 code was rejected by the narrowed validator (fault-injected) |
| `docs` | SUCCEEDED | 1 | Attempt 1 |
| `policy-eval` | SUCCEEDED | 1 | Attempt 1 |
| `release-gate` | SUCCEEDED | 0 | Gate approved |
| `summary` | SUCCEEDED | 1 | Attempt 1 |

## Governance and recovery events

| Seq | Action | Node | Detail |
|---|---|---|---|
| 16 | `ApprovalRequested` | `impact-approval` | Human decision required at gate impact-approval |
| 17 | `WorkflowSuspended` | `-` | Run parked at a human gate |
| 18 | `ApprovalDecided` | `impact-approval` | Impact analysis is complete: no wire change, ADR-017 supersedes ADR-004, and rol |
| 44 | `NodeFailed` | `regression` | a legacy base62 code was rejected by the narrowed validator (fault-injected) |
| 46 | `RollbackStarted` | `regression` | Reverting artifacts of regression |
| 47 | `RollbackCompleted` | `regression` | Artifacts reverted to their prior version |
| 48 | `SafeStop` | `regression` | Rolled back and awaiting a human decision: a legacy base62 code was rejected by  |
| 54 | `SafeStop` | `-` | One or more nodes safe-stopped |
| 55 | `WorkflowResumed` | `regression` | Rollback confirmed; the validator now accepts both alphabets, so the regression  |
| 56 | `WorkflowResumed` | `policy-eval` | Rollback confirmed; the validator now accepts both alphabets, so the regression  |
| 57 | `WorkflowResumed` | `release-gate` | Rollback confirmed; the validator now accepts both alphabets, so the regression  |
| 58 | `WorkflowResumed` | `summary` | Rollback confirmed; the validator now accepts both alphabets, so the regression  |
| 59 | `WorkflowResumed` | `-` | Rollback confirmed; the validator now accepts both alphabets, so the regression  |
| 67 | `ApprovalRequested` | `release-gate` | Human decision required at gate release-gate |
| 68 | `WorkflowSuspended` | `-` | Run parked at a human gate |
| 69 | `ApprovalDecided` | `release-gate` | Regression passes after the rollback and fix; ADR-017 is recorded and no wire co |

## Human decisions

| Gate | Decision | Actor | Rationale |
|---|---|---|---|
| `impact-approval` | APPROVE | madhu | Impact analysis is complete: no wire change, ADR-017 supersedes ADR-004, and rollback is a flag flip because n |
| `release-gate` | READY | madhu | Regression passes after the rollback and fix; ADR-017 is recorded and no wire contract changed. |

## Policy outcomes other than PASS

| Policy | Mandatory | Outcome | Reason |
|---|---|---|---|
| `LIC-001` | no | EXCEPTION_REQUESTED | No dependency licence report was produced for this run. Awaiting a human decision on  |
| `CC-001` | yes | NOT_APPLICABLE | This run does not change the API contract. |

## Replanning

_No replanning occurred in this run._

## Files in this bundle

| File | Contents |
|---|---|
| `input.md` | the requirement as submitted, and what to watch |
| `run-run_1a0ed631050_2.json` | final snapshot from `GET /workflows/{id}` |
| `history.json` | the transition journal, in order |
| `audit.jsonl` | the hash-chained audit trail |
| `graph.mmd` | Mermaid graph coloured by final node state |
| `artifacts.json` | every artifact produced, with its provenance |
| `gates.json` | every gate and its decision |

## Reproduce

```bash
java -jar app/target/agentic-url-shortener.jar
./scripts/demo-scenarios.sh brownfield
```

Run ids differ on every execution; the states, attempt counts and policy outcomes should not.
