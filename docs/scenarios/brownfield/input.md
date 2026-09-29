# Scenario B - Brownfield: base58 codes, legacy codes still resolve

> **SIMULATED INPUT.** This requirement was written for the assessment. It is not a real client
> requirement, and nothing downstream of it should be read as production evidence.

| Field | Value |
|---|---|
| Requirement id | `REQ-SB-001` |
| Classification | Brownfield |
| Run | `run_1a0ed8a1313_2` |
| Submitted by | madhu |

## The requirement, verbatim

> New codes must exclude the visually ambiguous characters 0, O, I and l by moving to a base58 alphabet. Existing base62 codes must continue to resolve. Validation must accept both alphabets for lookup but only base58 for newly minted codes.

## Why this scenario exists

A change to behaviour that already has users. The impact analysis has to be produced and approved *before* anything is changed - produced afterwards, the gate would be decorative. Migrating existing codes was rejected outright: a short link that stops working is the worst failure this system has, and it would be invisible until somebody hit it.

## Injected faults

The `regression` node is failed deliberately and permanently, to exercise the whole recovery path: rollback, safe stop, and a human decision to resume.

## What to watch

| Node or field | Expected |
|---|---|
| `impact-approval` | APPROVED before any change is applied |
| `regression` | failed, rolled back, safe-stopped, resumed, then succeeded |
| `CC-002 / CC-003` | ADR-017 recorded and impact analysis present, so both pass |
