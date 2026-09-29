# Architecture Decision Records

Each record states the options that were rejected and why, because a decision without its
alternatives is an assertion rather than a decision.

| ADR | Subject | Status |
|---|---|---|
| [ADR-001](ADR-001.md) | Application architecture | Accepted |
| [ADR-002](ADR-002.md) | Language and framework | Accepted |
| [ADR-003](ADR-003.md) | Persistence | Accepted |
| [ADR-004](ADR-004.md) | Short-code generation | Accepted, superseded in part by ADR-017 |
| [ADR-005](ADR-005.md) | Orchestration | Accepted |
| [ADR-006](ADR-006.md) | Workflow state | Accepted |
| [ADR-007](ADR-007.md) | Workflow definition | Accepted |
| [ADR-008](ADR-008.md) | Human approval | Accepted |
| [ADR-009](ADR-009.md) | Failure handling | Accepted |
| [ADR-010](ADR-010.md) | Recovery | Accepted |
| [ADR-011](ADR-011.md) | Replanning | Accepted |
| [ADR-012](ADR-012.md) | Observability | Accepted |
| [ADR-013](ADR-013.md) | Testing | Accepted |
| [ADR-014](ADR-014.md) | Local execution | Accepted |
| [ADR-015](ADR-015.md) | Authentication | Accepted |
| [ADR-016](ADR-016.md) | Analytics | Accepted |
| [ADR-017](ADR-017.md) | Short-code alphabet | Accepted - supersedes ADR-004 in part |

ADR-017 was created during the brownfield scenario and supersedes ADR-004 in part: base62 remains
accepted for lookup, while new codes are minted from base58.
