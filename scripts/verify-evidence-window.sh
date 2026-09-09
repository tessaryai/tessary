#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Does every drift finding's evidence come from the window its payload describes?
#
# The failure this catches: `recordShift` replaces a finding's payload on every re-fire, so a shift
# that persists reports its newest window. If the evidence still enumerates the window that OPENED the
# finding, Layer 2 audits a claim about August against traffic from June and reports the gap as the
# detector contradicting itself. Rows here are that gap, one line per finding.
#
# Expected output after the fix, on data swept by a build that carries it: "ok: N findings checked, 0
# outside their window". A finding already ruled on is skipped, because its evidence is deliberately
# frozen at the ruling and is not expected to track the payload any more.
#
# Usage: scripts/verify-evidence-window.sh [postgres-container]
set -euo pipefail

CONTAINER="${1:-tessary-postgres-1}"

read -r -d '' SQL <<'EOF' || true
WITH checked AS (
  SELECT f.id,
         f.classifier_key,
         (f.payload -> 'window' ->> 'opened_at')::timestamptz AS win_open,
         (f.payload -> 'window' ->> 'closed_at')::timestamptz AS win_close,
         MIN(s.event_ts) AS ev_first,
         MAX(s.event_ts) AS ev_last,
         COUNT(*)        AS refs
    FROM finding f
    JOIN finding_evidence fe ON fe.finding_id = f.id AND fe.role = 'member'
    JOIN span s              ON s.trace_id = fe.trace_id AND s.id = fe.span_id
   WHERE f.payload -> 'window' ->> 'opened_at' IS NOT NULL
     AND f.triage_action IS NULL
   GROUP BY 1, 2, 3, 4
)
SELECT id, classifier_key, win_open, win_close, ev_first, ev_last, refs
  FROM checked
 -- A ref outside the window on either side. Equality is fine: the bounds are the first and last
 -- sample's own event time, so the extremes sit exactly on them.
 WHERE ev_first < win_open OR ev_last > win_close
 ORDER BY win_open DESC;
EOF

TOTAL=$(docker exec "$CONTAINER" psql -U tessary -d tessary -At -c "
  SELECT COUNT(DISTINCT f.id) FROM finding f
    JOIN finding_evidence fe ON fe.finding_id = f.id AND fe.role = 'member'
   WHERE f.payload -> 'window' ->> 'opened_at' IS NOT NULL AND f.triage_action IS NULL")

BAD=$(docker exec "$CONTAINER" psql -U tessary -d tessary -At -c "$SQL" | grep -c . || true)

if [ "$BAD" -eq 0 ]; then
    echo "ok: $TOTAL un-ruled findings checked, 0 with evidence outside their window"
    exit 0
fi

echo "FAIL: $BAD of $TOTAL un-ruled findings carry evidence from outside their own window"
echo
docker exec "$CONTAINER" psql -U tessary -d tessary -c "$SQL"
exit 1
