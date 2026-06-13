#!/usr/bin/env bash

set -u -o pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP_URL="${APP_URL:-http://localhost:8080}"
TIMEOUT_SECONDS="${SMOKE_TIMEOUT_SECONDS:-45}"
CONTROLLERS_DIR="$ROOT_DIR/src/main/java/com/redis/workshop/controller"

TOTAL=0
PASSED=0
FAILED=0
SUITE_START=$(date +%s)

if ! command -v curl >/dev/null 2>&1; then
  echo "curl is required" >&2
  exit 1
fi

if ! command -v python3 >/dev/null 2>&1; then
  echo "python3 is required" >&2
  exit 1
fi

cleanup() {
  local exit_code=$?
  if curl -fsS --max-time 5 "$APP_URL/api/health" >/dev/null 2>&1; then
    curl -fsS -X POST --max-time "$TIMEOUT_SECONDS" "$APP_URL/api/reset-all" >/dev/null 2>&1 || true
  fi
  exit "$exit_code"
}
trap cleanup EXIT

validate_json_body() {
  python3 - "$1" <<'PY'
import json
import sys

path = sys.argv[1]
try:
    with open(path, "r", encoding="utf-8") as fh:
        data = json.load(fh)
except Exception as exc:
    print(f"invalid:{exc}")
    raise SystemExit(1)

if data is None:
    print("null")
    raise SystemExit(1)
if isinstance(data, dict):
    print(f"dict:{len(data)}")
    raise SystemExit(0 if data else 1)
if isinstance(data, list):
    print(f"list:{len(data)}")
    raise SystemExit(0 if data else 1)
if isinstance(data, str):
    size = len(data.strip())
    print(f"str:{size}")
    raise SystemExit(0 if size else 1)
print(type(data).__name__)
PY
}

print_header() {
  printf "%-18s %-6s %-38s %-6s %-10s %-8s %-7s\n" "CHECK" "METHOD" "PATH" "HTTP" "JSON" "TIME" "RESULT"
  printf "%-18s %-6s %-38s %-6s %-10s %-8s %-7s\n" "------------------" "------" "--------------------------------------" "------" "----------" "--------" "-------"
}

run_check() {
  local label="$1"
  local method="$2"
  local path="$3"
  local payload="${4:-}"
  local body_file meta http_code time_total elapsed_ms json_state preview result
  body_file="$(mktemp)"

  if [ -n "$payload" ]; then
    meta="$(curl -sS -X "$method" -H 'Content-Type: application/json' --data "$payload" -o "$body_file" -w '%{http_code} %{time_total}' --max-time "$TIMEOUT_SECONDS" "$APP_URL$path" 2>/dev/null || echo '000 0')"
  else
    meta="$(curl -sS -X "$method" -o "$body_file" -w '%{http_code} %{time_total}' --max-time "$TIMEOUT_SECONDS" "$APP_URL$path" 2>/dev/null || echo '000 0')"
  fi

  read -r http_code time_total <<< "$meta"
  elapsed_ms="$(awk -v t="$time_total" 'BEGIN { printf "%.0fms", t * 1000 }')"
  TOTAL=$((TOTAL + 1))

  if [ "$http_code" = "200" ] && json_state="$(validate_json_body "$body_file" 2>/dev/null)"; then
    PASSED=$((PASSED + 1))
    result="PASS"
  else
    FAILED=$((FAILED + 1))
    result="FAIL"
    if [ "$http_code" != "200" ]; then
      json_state="http"
    else
      json_state="invalid"
    fi
    preview="$(python3 - "$body_file" <<'PY'
import pathlib
import sys

text = pathlib.Path(sys.argv[1]).read_text(encoding='utf-8', errors='replace')
text = ' '.join(text.split())
print(text[:160])
PY
)"
    if [ -n "$preview" ]; then
      printf "  -> %s: %s\n" "$label" "$preview"
    fi
  fi

  printf "%-18s %-6s %-38s %-6s %-10s %-8s %-7s\n" "$label" "$method" "$path" "$http_code" "$json_state" "$elapsed_ms" "$result"
  rm -f "$body_file"
}

if [ -d "$CONTROLLERS_DIR" ]; then
  mapping_count="$(grep -R -E '@GetMapping|@PostMapping' "$CONTROLLERS_DIR" | wc -l | tr -d ' ')"
  printf "Discovered %s GET/POST controller mappings. Smoke suite exercises 19 canonical JSON endpoints (17 UCs + health + reset).\n\n" "$mapping_count"
fi

print_header
run_check "Health" "GET" "/api/health"
run_check "Reset-All" "POST" "/api/reset-all"
run_check "Session" "POST" "/api/session/login" '{"username":"user1","password":"password1"}'
run_check "CacheAside" "GET" "/api/cache/product/mortgage-fixed"
run_check "UserProfile" "POST" "/api/profile/load/U1001"
run_check "AuthToken" "POST" "/api/auth/login" '{"username":"user1","password":"password1"}'
run_check "RateLimit" "POST" "/api/ratelimit/check?clientId=smoke-e2e"
run_check "Fraud" "POST" "/api/fraud/evaluate" '{"cardNumber":"4111111111111111","amount":"1250.50","merchant":"SMOKE-TEST","country":"ES"}'
run_check "FeatureStore" "GET" "/api/features/inference/C1001"
run_check "GeoFinder" "GET" "/api/geo/branches"
run_check "Assistant" "GET" "/api/assistant/kb"
run_check "DocSearch" "GET" "/api/docs/search?q=payment&mode=full-text"
run_check "DistLock" "POST" "/api/lock/simulate" '{"resourceId":"smoke-lock"}'
run_check "Dedup" "POST" "/api/dedup/submit" '{"sender":"ES001","receiver":"ES002","amount":"99.95"}'
run_check "TxMonitor" "POST" "/api/transactions/simulate/anomaly"
run_check "AMS" "GET" "/api/ams/status"
run_check "Guardrails" "GET" "/api/guardrails/stats"
run_check "AiGateway" "GET" "/api/gateway/stats"
run_check "Agents" "GET" "/api/agents/status"

TOTAL_SECONDS=$(( $(date +%s) - SUITE_START ))
printf "\nSummary: %d/%d passed, %d failed, total time %ss\n" "$PASSED" "$TOTAL" "$FAILED" "$TOTAL_SECONDS"

if [ "$FAILED" -gt 0 ]; then
  exit 1
fi

exit 0