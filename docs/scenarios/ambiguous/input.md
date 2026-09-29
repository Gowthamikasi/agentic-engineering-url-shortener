# Scenario C - Ambiguous: 'expire after a while', 'popular links'

> **SIMULATED INPUT.** This requirement was written for the assessment. It is not a real client
> requirement, and nothing downstream of it should be read as production evidence.

| Field | Value |
|---|---|
| Requirement id | `REQ-SC-001` |
| Classification | Unclassified |
| Run | `run_1a0ed8a1bda_3` |
| Submitted by | madhu |

## The requirement, verbatim

> Links should expire after a while and we should show popular links.

## Why this scenario exists

A requirement that cannot be implemented as written. The system must stop and ask rather than guess, and `implement` must record zero attempts. The human answer then supersedes the normalized requirement, which invalidates and regenerates everything downstream of it under a new definition version.

## Injected faults

None. The pause is caused by the requirement itself, not by an injected fault.

## What to watch

| Node or field | Expected |
|---|---|
| `clarify` | AWAITING_APPROVAL - the run genuinely pauses here |
| `implement` | zero attempts while the gate is open |
| `definitionVersion` | 2 after the decision - the downstream closure was regenerated |
