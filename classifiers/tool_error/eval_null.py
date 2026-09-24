# SPDX-License-Identifier: Apache-2.0
"""Run 1 — the null case, and the run that replaces `decision_interval`. PROGRAM.md §12.

Real traffic, unmodified, replayed through the real detector. Everything that alarms is a false positive
by construction, so the false-alarm rate is measurable without a single label — which matters because
there is no gold set for "this failure rate moved for a bad reason" and there cannot be one.

    uv run python -m tool_error.eval_null --corpus data/tool_error/buckets.jsonl

The report is ARL0 per candidate threshold: CALLS BETWEEN FALSE ALARMS, which is directly comparable to
the ~250,000 that `arl.py` predicts analytically. **The gap between the two is the cost of burstiness**,
and it is the single number this run exists to produce — every figure in arl.py assumes independent
Bernoulli trials, and real tool failures are not.

The shipped detector derives each tool's threshold from `arl_target` and its baseline rate, so the
swept value is the threshold's FLOOR (`min_decision_interval`), capped at 12 like the config's. Each row
prints the thresholds the armed tools actually ran at: two rows with the same range are one run.

Read the lowest floor whose measured ARL0 clears the target. Nothing here writes back into
PROGRAM.md or the catalog blob; that edit is a decision, made by a human looking at this table.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

from . import bridge, corpus

#: Spanning the analytic answer (6.0) by a wide margin in both directions, because the whole point is to
#: discover that burstiness has moved it rather than to confirm a prior.
#: Floors above 12 are clamped to 12 by the config (MAX_DECISION_INTERVAL), so they would repeat that row.
DEFAULT_THRESHOLDS = (4.0, 5.0, 6.0, 7.0, 8.0, 10.0, 12.0)


def series_payload(rows: list[corpus.Bucket]) -> list[dict]:
    return [
        {
            "tool": tool,
            "buckets": [{"bucket": b.bucket, "calls": b.calls, "failures": b.failures} for b in buckets],
        }
        for tool, buckets in corpus.by_tool(rows).items()
    ]


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--corpus", required=True, help="JSONL exported with tool_error.corpus.EXPORT_SQL")
    ap.add_argument("--min-baseline-calls", type=int, default=500)
    ap.add_argument("--min-effect-size", type=float, default=0.05)
    args = ap.parse_args(argv)

    rows = corpus.load(args.corpus)
    if not rows:
        print(f"{args.corpus} is empty — nothing to measure.", file=sys.stderr)
        return 2
    calls, failures = corpus.totals(rows)
    series = series_payload(rows)
    print(f"corpus: {len(rows):,} buckets · {len(series)} tools · {calls:,} calls · {failures:,} failures")
    print(f"        overall failure rate {failures / calls:.4%}\n")

    print(f"{'floor':>6} {'thresholds run':>15} {'armed':>6} {'alarms':>7} {'calls armed':>13} {'measured ARL0':>15}")
    print("-" * 68)
    for h in DEFAULT_THRESHOLDS:
        results = bridge.replay(series, h, args.min_effect_size, args.min_baseline_calls)
        armed = [r for r in results if r.get("armed")]
        alarms = [r for r in armed if r.get("alarm_after_calls") is not None]
        # Calls the detector was actually watching: up to the alarm for a tool that fired, all of them
        # for one that did not. Counting a tool's whole history when it alarmed early would understate
        # the false-alarm rate by however long it then sat there.
        watched = sum(r.get("alarm_after_calls") or r.get("calls_seen") or 0 for r in armed)
        arl = f"{watched / len(alarms):,.0f}" if alarms else f">{watched:,}"
        ran = [r["threshold"] for r in armed if r.get("threshold") is not None]
        span = f"{min(ran):.1f}-{max(ran):.1f}" if ran else "-"
        print(f"{h:>6.1f} {span:>15} {len(armed):>6} {len(alarms):>7} {watched:>13,} {arl:>15}")

    print(
        "\nEvery alarm above is a FALSE alarm: this is unmodified traffic. Compare the measured ARL0"
        "\nagainst arl.py's analytic ~250,000 at h=6.0 — the shortfall is what burstiness costs, and it"
        "\nis the reason PROGRAM.md §9 refuses to call the shipped value measured."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
