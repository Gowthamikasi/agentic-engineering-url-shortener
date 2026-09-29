# Quickstart

Verified from a clean clone. The only prerequisite is **JDK 21**.

```bash
git clone <repo> && cd agentic-engineering-url-shortener
./mvnw verify                                     # 157 tests, ~2 minutes
java -jar app/target/agentic-url-shortener.jar    # http://localhost:8080
```

In a second terminal:

```bash
./scripts/demo-scenarios.sh                       # runs all three scenarios
```

Then read `docs/scenarios/*/outcome.md`.

## Contracts in this folder

| File | What it is |
|---|---|
| `contracts/openapi.v1.yaml` | The HTTP contract, v1.1.0. Also served live at `/v3/api-docs`. |
| `contracts/workflow-definition.v1.json` | The governed SDLC graph the engine executes. |
| `contracts/policy-set.v1.0.0.json` | The 13 guardrails every run is evaluated against. |

These are copies of the files the running system loads, kept here so a reviewer can read the
contracts without navigating the module tree. The authoritative copies live at
`orchestration-core/src/main/resources/workflows/` and `policies/`.

## If something fails

| Symptom | Cause |
|---|---|
| `release: 21` unsupported | Wrong JDK. Check with `java -version`. |
| `scripts/...: command not found` on Windows | Run the scripts from Git Bash, not cmd or PowerShell. |
| Port 8080 in use | `--server.port=8081`, and set `BASE_URL` for the demo script. |
| A node fails with "No test reports matched" | Run `./mvnw verify` first — that agent reads the build's real Surefire XML and will not report a pass it did not observe. |
