#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Burst absorption. Holds a safe baseline rate, then applies a spike of a given multiple for a given
# number of seconds, and records the second the first 503 appeared (if any).
#
# The model this is testing: the spool absorbs (spike_rate - drain_rate) x seconds worth of spans
# until the 64 MiB byte budget hits the 0.8 refuse fraction. So a short violent spike is survivable
# and a long mild one is not, and the crossover is what the number below has to state.
#
#   bash spike.sh <label> <baseline-spans-per-s> <multiplier> <spike-seconds> [more multipliers...]
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

LABEL="$1"; BASE="$2"; SPIKE_FOR="$3"; shift 3
TOKEN="$(head -1 tokens.txt)"
OUTDIR="results/$LABEL"; mkdir -p "$OUTDIR"
SETTLE=45   # baseline seconds before the spike, so the queue is at its steady state, not empty
TAIL=45     # baseline seconds after, to see whether it recovers

echo "multiplier,spike_spans_per_s,spike_seconds,excess_spans_offered,sent,ok,refused_503,first_503_at_s,recovered" > "$OUTDIR/spikes.csv"

for X in "$@"; do
  DUR=$((SETTLE + SPIKE_FOR + TAIL))
  echo "=== baseline ${BASE}/s, spike x${X} for ${SPIKE_FOR}s ==="
  node otlp-load.js --token "$TOKEN" --rate "$BASE" --spans-per-request "${SPANS_PER_REQ:-40}" \
    --payload-bytes "${PAYLOAD_BYTES:-2048}" --duration "$DUR" --warmup 0 \
    --spike-at "$SETTLE" --spike-x "$X" --spike-for "$SPIKE_FOR" \
    --label "$LABEL-x$X" --out "$OUTDIR/spike-x$X.jsonl" > "$OUTDIR/spike-x$X.json"

  python3 - "$OUTDIR/spike-x$X.jsonl" "$OUTDIR/spike-x$X.json" "$X" "$BASE" "$SPIKE_FOR" "$SETTLE" "$OUTDIR/spikes.csv" <<'PY'
import json, sys
rows = [json.loads(l) for l in open(sys.argv[1]) if l.strip()]
agg = json.load(open(sys.argv[2]))
x, base, dur, settle, csv = float(sys.argv[3]), float(sys.argv[4]), int(sys.argv[5]), int(sys.argv[6]), sys.argv[7]
first = next((r["sec"] for r in rows if r["refused"] > 0), None)
tail = [r for r in rows if r["sec"] > settle + dur + 10]
recovered = "yes" if tail and all(r["refused"] == 0 for r in tail) else ("n/a" if not tail else "no")
excess = round((base * x - base) * dur)
open(csv, "a").write(",".join(str(v) for v in [
    x, round(base * x), dur, excess, agg["sent"], agg["ok"], agg["refused"],
    (first - settle) if first is not None else "none", recovered]) + "\n")
print(f"  x{x}: offered {round(base*x)}/s for {dur}s, excess {excess} spans -> "
      f"503s={agg['refused']} first at t+{(first - settle) if first is not None else 'never'}s, recovered={recovered}")
PY
  sleep 30
done

echo
column -s, -t "$OUTDIR/spikes.csv"
