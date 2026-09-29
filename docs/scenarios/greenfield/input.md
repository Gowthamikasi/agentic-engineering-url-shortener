# Scenario A - Greenfield: optional link expiration

> **SIMULATED INPUT.** This requirement was written for the assessment. It is not a real client
> requirement, and nothing downstream of it should be read as production evidence.

| Field | Value |
|---|---|
| Requirement id | `REQ-SA-001` |
| Classification | Greenfield |
| Run | `run_1a0edff9acf_1` |
| Submitted by | madhu |

## The requirement, verbatim

> A client may supply expiresAt (ISO-8601 UTC) when creating a link. A redirect for an expired link returns 410 Gone with a JSON problem body. An expiresAt in the past is rejected with 400. Stats must still show total clicks for an expired link.

## Why this scenario exists

A complete, consistent, testable requirement. The point of this scenario is what the system does *not* do: it must run end to end without arming a clarification gate. A system that asks a question on every run teaches its reviewers to approve without reading, which is worse than one that never asks (REQ-D-009).

## Injected faults

A transient failure is injected into `contract-tests` so a bounded retry, a recovery and a measurable recovery duration all appear in the journal. Real reliability behaviour cannot be demonstrated by waiting for a real failure, and writing the numbers by hand would not be evidence.

## What to watch

| Node or field | Expected |
|---|---|
| `clarify` | SKIPPED - no artificial gate on a clear requirement |
| `contract-tests` | two attempts - the injected transient failure recovered on retry |
| `impact-analysis / impact-approval / regression` | SKIPPED - not a brownfield change |
