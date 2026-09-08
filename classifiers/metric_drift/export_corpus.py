# SPDX-License-Identifier: Apache-2.0
"""Export one project's traffic into the corpus every run in this module reads — PLAN.md §9.1's A1.

This exists because the export is the one step of the calibration that nothing downstream can check.
A bridge that cannot start fails loudly; a floor read off the wrong population reads exactly like a
floor read off the right one. So the two queries in `corpus.py` are run here, from the constants
themselves, with the parameters they need supplied rather than remembered — and then the export is
described back to you in the terms that decide whether a null run off it means anything at all.

    export DATABASE_URL=postgres://...
    uv run python -m metric_drift.export_corpus --project-id prj_... --since 2026-05-01

`psql` rather than a driver: the queries carry `:name` placeholders in psql's own dialect, they are
copied into the README for anyone exporting by hand, and one placeholder style across both routes is
worth more than dropping a binary this repo's own backup scripts already assume.

The summary at the end answers, before an hour is spent on the runs:

- **Is there enough traffic to compare anything?** A bucket needs `min_sample` readings twice before
  a single comparison happens. A corpus where no bucket clears that bar produces a null run that is
  silent for the one reason a silent null run must never be read as: it measured nothing.
- **Is there a deploy boundary in here?** Run 3 has nothing to attribute to without one.
- **How much of it abstains?** An unpriced model abstains the whole turn, and a corpus that is
  mostly abstentions is a cost floor measured on the minority of traffic that happens to be priced.
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
from collections import Counter
from pathlib import Path

from metric_drift.corpus import TOOL_EXPORT_SQL, TURN_EXPORT_SQL

#: MetricDriftConfig.DEFAULT_SETTLE_SECONDS. Duplicated rather than read over the bridge because the
#: export runs before anything else and a JVM start to fetch one integer is not worth the coupling.
#: If it moves in Java and not here, the corpus holds a few extra minutes of the newest traffic.
DEFAULT_SETTLE_SECONDS = 300

#: MetricDriftConfig.DEFAULT_MIN_SAMPLE, for the feasibility count below only. Nothing is filtered on
#: it — a thin bucket is a fact about the corpus to report, not a row to drop.
DEFAULT_MIN_SAMPLE = 150


def _psql(database_url: str, sql: str, variables: dict[str, str]) -> list[str]:
    """One query, one JSON object per row. Fails loudly; a partial corpus is worse than none.

    The query goes in on STDIN rather than through ``-c``. psql performs ``:name`` interpolation only
    on input it reads as a script; a ``-c`` string is handed to the server verbatim, so every
    placeholder in ``corpus.py`` reached Postgres literally and the export died on
    ``syntax error at or near ":"`` before it read a single row.
    """
    argv = ["psql", database_url, "-Aqt", "-v", "ON_ERROR_STOP=1"]
    for key, value in variables.items():
        argv += ["-v", f"{key}={value}"]
    argv += ["-f", "-"]
    proc = subprocess.run(
        argv, input=f"SELECT row_to_json(r) FROM ({sql}) r;", capture_output=True, text=True, check=False
    )
    if proc.returncode != 0:
        raise SystemExit(f"psql failed ({proc.returncode}):\n{proc.stderr.strip()}")
    return [line for line in proc.stdout.splitlines() if line.strip()]


def _summarize(rows: list[str]) -> None:
    """What the corpus can and cannot support, in the terms the three runs are read in."""
    turns = [json.loads(line) for line in rows]
    per_call_site: Counter[str] = Counter()
    versions: set[str] = set()
    no_duration = 0
    no_cost = 0
    for turn in turns:
        per_call_site[turn.get("call_site_id") or "__unattributed__"] += 1
        if turn.get("project_version_id"):
            versions.add(turn["project_version_id"])
        if turn.get("duration_ms") is None:
            no_duration += 1
        # cost_usd is null on every production row and is resolved from `usage_leaves` at load time,
        # so a turn with neither is one that will abstain — which is what is worth counting here.
        if turn.get("cost_usd") is None and not turn.get("usage_leaves"):
            no_cost += 1

    comparable = [site for site, n in per_call_site.items() if n >= 2 * DEFAULT_MIN_SAMPLE]
    print(f"\nturns              {len(turns)}")
    print(f"call sites         {len(per_call_site)}")
    print(f"  comparable       {len(comparable)}  (>= {2 * DEFAULT_MIN_SAMPLE} turns: two windows over min_sample)")
    print(f"deploy boundaries  {len(versions)}")
    print(f"no duration        {no_duration}  ({_pct(no_duration, len(turns))}) — unfinished turns, abstain")
    print(f"no cost basis      {no_cost}  ({_pct(no_cost, len(turns))}) — no rollup and no llm leaves, abstain")

    if not comparable:
        print(
            "\nNOT ENOUGH TRAFFIC. No call site clears min_sample twice, so the null run would make"
            "\nzero comparisons and report silence. That is not a low false-positive rate — it is no"
            "\nmeasurement. Export a longer stretch."
        )
    if len(versions) < 2:
        print("\nNo deploy boundary in this stretch — run 3 has nothing to attribute to. Runs 1 and 2 are unaffected.")


def _pct(part: int, whole: int) -> str:
    return f"{(100.0 * part / whole):.1f}%" if whole else "n/a"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--project-id", required=True, help="the project to export, e.g. prj_...")
    parser.add_argument("--database-url", default=os.environ.get("DATABASE_URL"), help="defaults to $DATABASE_URL")
    parser.add_argument("--out", default="data/metric_drift/turns.jsonl", type=Path)
    parser.add_argument("--since", default="-infinity", help="lower bound on trace.created_at; default everything")
    parser.add_argument("--settle-seconds", type=int, default=DEFAULT_SETTLE_SECONDS)
    args = parser.parse_args()

    if not args.database_url:
        parser.error("no --database-url and no $DATABASE_URL")

    variables = {
        "project_id": f"'{args.project_id}'",
        "since": f"'{args.since}'",
        "settle_seconds": str(args.settle_seconds),
    }

    print(f"turns  ... ", end="", flush=True)
    turn_rows = _psql(args.database_url, TURN_EXPORT_SQL, variables)
    print(f"{len(turn_rows)} rows")
    print(f"tools  ... ", end="", flush=True)
    tool_rows = _psql(args.database_url, TOOL_EXPORT_SQL, variables)
    print(f"{len(tool_rows)} rows")

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text("\n".join([*turn_rows, *tool_rows]) + "\n")
    print(f"wrote {args.out}")

    _summarize(turn_rows)
    return 0


if __name__ == "__main__":
    sys.exit(main())
