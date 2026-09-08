# SPDX-License-Identifier: Apache-2.0
"""The corpus the null run replays: hourly (tool, calls, failures) buckets, exported from the platform.

PROGRAM.md §12. Deliberately the SAME shape the detector reads in production — `ToolErrorRepository`
returns exactly these four fields — so the harness measures the buckets the classifier would see rather
than a reconstruction of them.

The export SQL below carries `ToolFailure.SQL_PREDICATE` verbatim. That is the whole point and the one
thing to check when editing either: a corpus counted under a different definition of failure measures a
detector nobody ships, which is the mistake `classifiers/metric_drift/README.md` records behaviour drift
having made the expensive way.

    psql "$DATABASE_URL" -Aqt -v project_id="'prj_...'" \\
      -c "$(uv run python -c 'from tool_error.corpus import EXPORT_SQL as q; print(q)')" \\
      > data/tool_error/buckets.jsonl
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path

#: Kept character-for-character in step with ToolFailure.SQL_PREDICATE. Four rules: the span status, an
#: OTel error.type attribute, a recorded exception.type, and a result that declares itself an error.
FAILURE_PREDICATE = """
(tc.error_type IS NOT NULL
 OR tc.is_error IS TRUE
 OR o.attributes ->> 'error.type' IS NOT NULL
 OR o.attributes ->> 'exception.type' IS NOT NULL
 OR (tc.result IS NOT NULL
     AND jsonb_typeof(tc.result) = 'object'
     AND (tc.result @> '{"isError": true}'::jsonb
          OR (tc.result ? 'error' AND jsonb_typeof(tc.result -> 'error') <> 'null'))))
""".strip()

#: Event time, not ingest time — a backfill lands a corpus in one burst and on the ingest clock a single
#: bucket would swallow a month. Mirrors ToolErrorRepository.EVENT_AT.
EVENT_AT = "COALESCE(tc.event_ts, tc.started_at, tc.created_at)"

EXPORT_SQL = f"""
SELECT row_to_json(r) FROM (
  SELECT to_char(date_trunc('hour', {EVENT_AT}), 'YYYY-MM-DD"T"HH24:MI:SS"Z"') AS bucket,
         COALESCE(NULLIF(tc.name, ''), 'unnamed') AS tool,
         COUNT(*) AS calls,
         COUNT(*) FILTER (WHERE {FAILURE_PREDICATE}) AS failures
  FROM tool_call tc
  JOIN observation o ON o.id = tc.observation_id
  WHERE tc.project_id = :project_id
    AND tc.is_deleted IS NOT TRUE
    AND o.is_deleted IS NOT TRUE
  GROUP BY 1, 2
  ORDER BY 1 ASC
) r
""".strip()


@dataclass(frozen=True)
class Bucket:
    """One tool's outcomes in one hour. The unit the replay consumes."""

    bucket: str
    tool: str
    calls: int
    failures: int


def load(path: str | Path) -> list[Bucket]:
    """Read an exported JSONL corpus, oldest first.

    Sorted here rather than trusted from the file: a CUSUM fed out of order is not a CUSUM, and an export
    concatenated from two runs is the obvious way to get an unsorted one.
    """
    rows: list[Bucket] = []
    for line in Path(path).read_text().splitlines():
        line = line.strip()
        if not line:
            continue
        d = json.loads(line)
        calls = int(d["calls"])
        failures = min(int(d["failures"]), calls)
        rows.append(Bucket(d["bucket"], d["tool"], calls, failures))
    rows.sort(key=lambda b: b.bucket)
    return rows


def by_tool(rows: list[Bucket]) -> dict[str, list[Bucket]]:
    out: dict[str, list[Bucket]] = {}
    for r in rows:
        out.setdefault(r.tool, []).append(r)
    return out


def totals(rows: list[Bucket]) -> tuple[int, int]:
    return sum(r.calls for r in rows), sum(r.failures for r in rows)
