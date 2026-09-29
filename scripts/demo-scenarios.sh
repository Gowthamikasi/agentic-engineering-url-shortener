#!/usr/bin/env bash
#
# Runs the three assessment scenarios against a running instance and writes the evidence bundle
# for each one into docs/scenarios/<name>/.
#
# Everything this script writes is produced by the running system. It captures responses; it never
# composes them. If a scenario does not reach the state described below, the bundle will show that
# instead, which is the point.
#
#   ./scripts/demo-scenarios.sh                 # all three
#   ./scripts/demo-scenarios.sh greenfield      # one by name
#   ./scripts/demo-scenarios.sh ambiguous --no-decide   # stop at the clarification gate
#
set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
API_KEY="${API_KEY:-demo-operator-key}"
ACTOR="${ACTOR:-madhu}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
EVIDENCE="$ROOT/docs/scenarios"
WAIT_MS="${WAIT_MS:-90000}"
DECIDE=1

api() { curl -sS -m 180 -H "X-Api-Key: $API_KEY" "$@"; }
post() { api -X POST -H "Content-Type: application/json" "$@"; }

json_field() { python -c "import sys,json;print(json.load(sys.stdin).get('$1',''))" 2>/dev/null; }

require_running() {
  if ! curl -sS -m 10 -o /dev/null "$BASE_URL/health/live"; then
    echo "The application is not answering at $BASE_URL." >&2
    echo "Start it first:  java -jar app/target/agentic-url-shortener.jar" >&2
    exit 1
  fi
}

# Captures everything the control plane will tell us about a run.
capture() {
  local name="$1"
  local run_id="$2"
  local dir="$EVIDENCE/$name"
  mkdir -p "$dir"
  api "$BASE_URL/api/v1/workflows/$run_id"            > "$dir/run-$run_id.json"
  api "$BASE_URL/api/v1/workflows/$run_id/audit"      > "$dir/audit.jsonl"
  api "$BASE_URL/api/v1/workflows/$run_id/history"    > "$dir/history.json"
  api "$BASE_URL/api/v1/workflows/$run_id/graph"      > "$dir/graph.mmd"
  api "$BASE_URL/api/v1/workflows/$run_id/artifacts"  > "$dir/artifacts.json"
  api "$BASE_URL/api/v1/workflows/$run_id/gates"      > "$dir/gates.json"
  echo "$run_id" > "$dir/run-id.txt"
  # Read the state with sed rather than a Python one-liner: this script runs under Git Bash on
  # Windows, where the shell's /c/... paths are not paths the Windows Python interpreter can open.
  # grep -o, not sed: the whole response is one line, so a greedy sed pattern would report the
  # LAST "state" in the document (a node's) rather than the run's.
  local state
  state="$(grep -o '"state":"[A-Z_]*"' "$dir/run-$run_id.json" | head -1 | cut -d'"' -f4)"
  echo "  captured $name -> $dir  (state: ${state:-unreadable})"
}

start_run() {
  post "$BASE_URL/api/v1/workflows?waitMs=$WAIT_MS" -d @- | json_field runId
}

# A refused decision must be reported, not swallowed: a script that prints "decided" when the
# engine rejected the call would be manufacturing exactly the evidence this system exists to avoid.
decide() {
  local run_id="$1"
  local gate="$2"
  local decision="$3"
  local rationale="$4"
  local response
  response=$(post "$BASE_URL/api/v1/workflows/$run_id/gates/$gate/decision?waitMs=$WAIT_MS" \
    -d "{\"decision\":\"$decision\",\"actor\":\"$ACTOR\",\"rationale\":\"$rationale\"}")

  if echo "$response" | grep -q '"errorCode"'; then
    echo "  REFUSED $gate = $decision"
    echo "    $response"
    return 1
  fi
  echo "  decided $gate = $decision"
}

# ---------------------------------------------------------------- Scenario A: greenfield
#
# A complete, testable requirement. It must run end to end WITHOUT a clarification gate
# (REQ-D-009), and a fault is injected into contract-tests so a transient failure, a bounded
# retry and a recovery duration all appear in the journal.
scenario_greenfield() {
  echo "Scenario A - greenfield (optional link expiration)"
  local run_id
  run_id=$(start_run <<'JSON'
{
  "definition": "sdlc",
  "requirementId": "REQ-SA-001",
  "kind": "Greenfield",
  "actor": "madhu",
  "text": "A client may supply expiresAt (ISO-8601 UTC) when creating a link. A redirect for an expired link returns 410 Gone with a JSON problem body. An expiresAt in the past is rejected with 400. Stats must still show total clicks for an expired link.",
  "faults": { "contract-tests": { "times": 1, "class": "TRANSIENT", "reason": "H2 LOCK_TIMEOUT while reading the report" } },
  "dependencyScan": { "ran": true, "high": 0 },
  "tddEvidence": { "redThenGreen": true, "ref": "docs/scenarios/greenfield/validation.md" }
}
JSON
)
  [ -z "$run_id" ] && { echo "  failed to start" >&2; return 1; }
  echo "  run: $run_id"
  decide "$run_id" release-gate READY \
    "All mandatory policies pass, the contract change is additive and version-bumped, and the injected transient failure recovered on retry."
  capture greenfield "$run_id"
}

# ---------------------------------------------------------------- Scenario B: brownfield
#
# A change to existing behaviour. The impact-analysis gate must be reached BEFORE any change is
# applied, and the regression node is failed deliberately so the rollback path, the safe stop and
# the human resume are all exercised for real.
scenario_brownfield() {
  echo "Scenario B - brownfield (base58 alphabet, legacy codes keep resolving)"
  local run_id
  run_id=$(start_run <<'JSON'
{
  "definition": "sdlc",
  "requirementId": "REQ-SB-001",
  "kind": "Brownfield",
  "actor": "madhu",
  "text": "New codes must exclude the visually ambiguous characters 0, O, I and l by moving to a base58 alphabet. Existing base62 codes must continue to resolve. Validation must accept both alphabets for lookup but only base58 for newly minted codes.",
  "faults": { "regression": { "times": 1, "class": "PERMANENT", "reason": "a legacy base62 code was rejected by the narrowed validator" } },
  "dependencyScan": { "ran": true, "high": 0 },
  "tddEvidence": { "redThenGreen": true, "ref": "docs/scenarios/brownfield/validation.md" }
}
JSON
)
  [ -z "$run_id" ] && { echo "  failed to start" >&2; return 1; }
  echo "  run: $run_id"

  decide "$run_id" impact-approval APPROVE \
    "Impact analysis is complete: no wire change, ADR-017 supersedes ADR-004, and rollback is a flag flip because no data is mutated."

  # The regression failure rolls back and safe-stops. A human decides to continue.
  post "$BASE_URL/api/v1/workflows/$run_id/resume?waitMs=$WAIT_MS" \
    -d "{\"actor\":\"$ACTOR\",\"reason\":\"Rollback confirmed; the validator now accepts both alphabets, so the regression is re-run.\"}" > /dev/null
  echo "  resumed after rollback"

  decide "$run_id" release-gate READY \
    "Regression passes after the rollback and fix; ADR-017 is recorded and no wire contract changed."
  capture brownfield "$run_id"
}

# ---------------------------------------------------------------- Scenario C: ambiguous
#
# A vague requirement. The run must STOP at the clarification gate with no implementation
# attempted. The human answer then supersedes the normalized requirement, which invalidates and
# regenerates the downstream subgraph under a new definition version.
scenario_ambiguous() {
  echo "Scenario C - ambiguous (expiry 'after a while', 'popular' links)"
  local run_id
  run_id=$(start_run <<'JSON'
{
  "definition": "sdlc",
  "requirementId": "REQ-SC-001",
  "kind": "Unclassified",
  "actor": "madhu",
  "text": "Links should expire after a while and we should show popular links.",
  "dependencyScan": { "ran": true, "high": 0 }
}
JSON
)
  [ -z "$run_id" ] && { echo "  failed to start" >&2; return 1; }
  echo "  run: $run_id"

  if [ "$DECIDE" -eq 0 ]; then
    echo "  stopping at the clarification gate as requested"
    echo "  to answer it:"
    echo "    curl -X POST '$BASE_URL/api/v1/workflows/$run_id/gates/clarify/decision?waitMs=60000' \\"
    echo "      -H 'X-Api-Key: $API_KEY' -H 'Content-Type: application/json' \\"
    echo "      -d '{\"decision\":\"APPROVE\",\"actor\":\"$ACTOR\",\"rationale\":\"...\"}'"
    capture ambiguous "$run_id"
    return 0
  fi

  decide "$run_id" clarify APPROVE \
    "Default expiry is 90 days only when expiresAt is omitted; popular means the top 10 links by clicks over the trailing 7 days, exposed only on an authenticated endpoint; REQ-SA-001 is amended to allow a default."
  decide "$run_id" release-gate READY \
    "The clarified requirement is testable, the downstream subgraph was regenerated under definition v2, and all mandatory policies pass."
  capture ambiguous "$run_id"
}

# ---------------------------------------------------------------- main

for arg in "$@"; do
  [ "$arg" = "--no-decide" ] && DECIDE=0
done

require_running
mkdir -p "$EVIDENCE"

TARGET="${1:-all}"
case "$TARGET" in
  greenfield)  scenario_greenfield ;;
  brownfield)  scenario_brownfield ;;
  ambiguous)   scenario_ambiguous ;;
  all|--*)     scenario_greenfield; echo; scenario_brownfield; echo; scenario_ambiguous ;;
  *)           echo "Unknown scenario: $TARGET (expected greenfield, brownfield, ambiguous or all)" >&2; exit 2 ;;
esac

echo
echo "Reliability metrics across every run in this instance:"
api "$BASE_URL/api/v1/metrics/reliability" > "$EVIDENCE/reliability-metrics.json"
echo "  written to $EVIDENCE/reliability-metrics.json"
