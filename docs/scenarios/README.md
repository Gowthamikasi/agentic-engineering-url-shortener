# Scenario evidence

Three runs, executed by the system. **Every file here is captured output** — API responses written
straight to disk by `scripts/demo-scenarios.sh`. Nothing was composed by hand, including the
`outcome.md` files, which are generated from the captured bundles.

Run ids change on every execution. States, attempt counts and policy outcomes should not.

| Scenario | What it demonstrates | Outcome |
|---|---|---|
| [greenfield](greenfield/) | A clear requirement runs end to end with **no** clarification gate; a transient failure is retried and recovered | COMPLETED |
| [brownfield](brownfield/) | Impact analysis approved **before** any change; a permanent failure rolls back, safe-stops, and is resumed by a human | COMPLETED |
| [ambiguous](ambiguous/) | The run **stops and asks** instead of guessing; the answer invalidates and regenerates the downstream subgraph at definition v2 | COMPLETED at v2 |

`reliability-metrics.json` covers all three runs and is recomputed from the journal on request.

## Reproduce

```bash
java -jar app/target/agentic-url-shortener.jar
./scripts/demo-scenarios.sh

# or stop at the clarification gate and answer it yourself
./scripts/demo-scenarios.sh ambiguous --no-decide
```

## Injected faults

Two failures in these runs were injected on purpose, declared in the run input and marked
`(fault-injected)` in the audit trail:

| Run | Node | Class | Why |
|---|---|---|---|
| greenfield | `contract-tests` | TRANSIENT | to show a bounded retry and a measured recovery duration |
| brownfield | `regression` | PERMANENT | to show rollback, safe stop and human resume |

Reliability behaviour cannot be demonstrated without failures. Waiting for a real one would produce
no evidence, and writing the numbers by hand would produce false evidence — so the failures are
real, caused deliberately, and visible as such in the trail and in the metrics population.
