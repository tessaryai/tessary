#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Drives otlp-load.js up a ladder of offered rates against the running reference stack and records,
# per step: the generator's own status-code counts, the rows that actually reached Postgres, the
# heartbeat's queue depth, and container CPU/memory.
#
#   bash ramp.sh <label> <duration-per-step-seconds> <rate> [rate...]
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
ROOT="$(cd ../.. && pwd)"

LABEL="$1"; shift
STEP="$1"; shift
TOKEN="$(head -1 tokens.txt)"
OUTDIR="results/$LABEL"
mkdir -p "$OUTDIR"

pg() { docker exec tessary-postgres-1 psql -U "${POSTGRES_USER:-tessary}" -d "${POSTGRES_DB:-tessary}" -tAc "$1"; }
COMPOSE=(docker compose -f "$ROOT/docker-compose.yml" -f "$ROOT/docker-compose.bench.yml")

spans_now() { pg "select count(*) from span" | tr -d ' '; }

# docker stats is a one-shot sample per call; sample it on a timer for the length of the step.
sample_stats() {
  local out="$1" secs="$2"
  local end=$((SECONDS + secs))
  while [ "$SECONDS" -lt "$end" ]; do
    docker stats --no-stream --format '{{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.MemPerc}}' \
      tessary-backend-1 tessary-postgres-1 2>/dev/null | sed "s/^/$(date +%s)\t/" >> "$out" || true
    sleep 2
  done
}

echo "step,offered_spans_per_s,sent,ok,refused_503,other,errors,skipped,rows_written,drain_spans_per_s,p50_ms,p95_ms,p99_ms" > "$OUTDIR/steps.csv"

for RATE in "$@"; do
  echo "=== step: ${RATE} spans/s offered, ${STEP}s ==="
  before="$(spans_now)"
  : > "$OUTDIR/stats-$RATE.tsv"
  sample_stats "$OUTDIR/stats-$RATE.tsv" "$STEP" &
  stats_pid=$!
  node otlp-load.js --token "$TOKEN" --rate "$RATE" --spans-per-request "${SPANS_PER_REQ:-40}" \
    --payload-bytes "${PAYLOAD_BYTES:-2048}" --duration "$STEP" --warmup "${WARMUP:-10}" \
    --label "$LABEL-$RATE" --out "$OUTDIR/load-$RATE.jsonl" > "$OUTDIR/summary-$RATE.json"
  wait "$stats_pid" 2>/dev/null || true

  # Let the queue drain before counting rows, so the number is what the run delivered rather than
  # where the queue happened to be when the generator stopped.
  sleep 20
  after="$(spans_now)"
  written=$((after - before))
  python3 - "$OUTDIR/summary-$RATE.json" "$RATE" "$written" "$STEP" "$OUTDIR/steps.csv" <<'PY'
import json, sys
s = json.load(open(sys.argv[1])); rate, written, step, csv = sys.argv[2], int(sys.argv[3]), int(sys.argv[4]), sys.argv[5]
measured = round(written / step, 1)
row = [rate, s["measured_span_rate"], s["sent"], s["ok"], s["refused"], s["other"], s["error"],
       s["skipped"], written, measured, s["latency_ms"]["p50"], s["latency_ms"]["p95"], s["latency_ms"]["p99"]]
open(csv, "a").write(",".join(str(x) for x in row) + "\n")
warn = "  *** SKIPPED>0: max-inflight was hit, this step was not open-loop ***" if s["skipped"] else ""
print(f"  offered={s['measured_span_rate']}/s  ok={s['ok']}  503={s['refused']}  other={s['other']}  "
      f"rows={written} ({measured}/s)  p99={s['latency_ms']['p99']}ms{warn}")
PY
  # The heartbeat lines that cover this step, for queue depth and shed counts.
  "${COMPOSE[@]}" logs backend --since "$((STEP + 40))s" --no-color 2>/dev/null \
    | grep '"event":"ingest.throughput"' > "$OUTDIR/heartbeat-$RATE.jsonl" || true
done

echo
column -s, -t "$OUTDIR/steps.csv"
