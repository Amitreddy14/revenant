#!/usr/bin/env bash
# The kill -9 demo for the Docker deployment (deploy/docker-compose.prod.yml), run on the VM.
# Same idea as demo-kill-worker.ps1: start an order, SIGKILL the worker container while it is
# shipping, start a new worker, and check that every finished step ran exactly once.
#
#   cd deploy && cp .env.example .env && (edit it) && docker compose up -d --build
#   bash ../scripts/demo-kill-worker.sh
set -u

cd "$(dirname "$0")/../deploy" || exit 2
[ -f .env ] || { echo "deploy/.env not found. Copy .env.example and fill it in."; exit 2; }
set -a; . ./.env; set +a
: "${REVENANT_API_KEY:?REVENANT_API_KEY is not set in deploy/.env}"

compose() { docker compose "$@"; }
# Calls the API from inside the server container, so no domain or certificate is needed.
api() { compose exec -T server curl -sS -H "Authorization: Bearer $REVENANT_API_KEY" "$@"; }
# The ledger lives in the worker's volume; read it through a throwaway container.
ledger() { docker run --rm -v revenant-prod_worker-data:/data alpine cat /data/ledger.txt 2>/dev/null || true; }

say() { printf '\n== %s\n' "$1"; }
FAILED=0
check() { # name expected actual
  if [ "$2" = "$3" ]; then printf '   PASS  %-42s expected %s, got %s\n' "$1" "$2" "$3"
  else printf '   FAIL  %-42s expected %s, got %s\n' "$1" "$2" "$3"; FAILED=1; fi
}

say "Starting a fresh worker (A)"
compose --profile demo rm -sf worker >/dev/null 2>&1
compose --profile demo run -d --no-deps --name revenant-worker-a worker >/dev/null || { echo "could not start worker A"; exit 1; }
trap 'docker rm -f revenant-worker-a revenant-worker-b >/dev/null 2>&1' EXIT
sleep 5

ORDER="A-$(date +%Y%m%d%H%M%S)"
say "Starting order $ORDER: charge -> reserve stock -> ship (slow) -> send receipt"
RESPONSE=$(api -X POST "http://localhost:8080/api/v1/workflows?type=OrderFulfilment" \
  -H 'Content-Type: application/json' \
  -d "{\"orderId\":\"$ORDER\",\"amount\":4999,\"item\":\"book\"}")
WID=$(printf '%s' "$RESPONSE" | grep -o '"workflowId":"[^"]*"' | cut -d'"' -f4)
[ -n "$WID" ] || { echo "start failed: $RESPONSE"; exit 1; }
echo "workflow $WID"

for _ in $(seq 1 90); do
  ledger | grep -q "ship STARTED order=$ORDER" && break
  sleep 1
done
sleep 2

say "Killing worker A with SIGKILL (no warning, no cleanup)"
docker kill -s KILL revenant-worker-a >/dev/null
echo "worker A is dead"
STATUS=$(api "http://localhost:8080/api/v1/workflows/$WID" | grep -o '"status":"[A-Z_]*"')
echo "the server still knows the workflow: $STATUS"
echo "nobody reported the shipping step; its 10 s lease must run out first"
docker rm -f revenant-worker-a >/dev/null 2>&1

say "Starting worker B (it knows nothing about worker A)"
START=$(date +%s)
compose --profile demo run -d --no-deps --name revenant-worker-b worker >/dev/null || { echo "could not start worker B"; exit 1; }
for _ in $(seq 1 120); do
  STATUS=$(api "http://localhost:8080/api/v1/workflows/$WID" | grep -o '"status":"[A-Z_]*"' | cut -d'"' -f4)
  [ "$STATUS" != "RUNNING" ] && break
  sleep 1
done
echo "finished $(( $(date +%s) - START )) seconds after worker B started, status $STATUS"

say "What each step really did (the ledger)"
ledger | grep -- "$ORDER" | sed 's/^/   /'

say "Checking the result"
HISTORY=$(api "http://localhost:8080/api/v1/workflows/$WID")
COMPLETED=$(printf '%s' "$HISTORY" | grep -o '"type":"ActivityCompleted"' | wc -l | tr -d ' ')
cnt() { ledger | grep -c -- "$1" || true; }
check "workflow status"                      COMPLETED "$STATUS"
check "charge ran"                           1 "$(cnt "charge order=$ORDER ")"
check "reserve stock ran"                    1 "$(cnt "reserve order=$ORDER ")"
check "ship started (killed once, then again)" 2 "$(cnt "ship STARTED order=$ORDER")"
check "ship finished"                        1 "$(cnt "ship DONE order=$ORDER")"
check "receipt sent"                         1 "$(cnt "receipt tracking=TRK-$ORDER")"
check "steps recorded as completed"          4 "$COMPLETED"
echo
if [ "$FAILED" -eq 0 ]; then echo "RESULT: PASS - the worker died mid-shipping, and the charge was not repeated."
else echo "RESULT: FAIL - see the lines above."; fi
exit "$FAILED"
