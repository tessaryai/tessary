# SPDX-License-Identifier: Apache-2.0
"""Run 1 — the null case, and the run that sets `w1_floor`. PROGRAM.md §12.2, PLAN.md §9.1.

Real traffic, unmodified, replayed through the real detector. **Everything that fires is a false
positive by construction**, so the false-positive rate at a candidate floor is measurable without a
single label — which matters because there is no gold set for "this distribution moved for a bad
reason" and there cannot be one.

Until this run lands against a real corpus, `MetricDriftConfig.DEFAULT_W1_FLOOR` is a guess (0.18,
"about a 20% move") and both metric-drift classifiers seed disabled. That is the honest posture for a
number nobody has measured, and this script is how it stops being one.

    uv run python -m metric_drift.eval_null --corpus data/metric_drift/turns.jsonl

The report prints, per measure and per candidate floor, how many findings the run produced and over
how many comparisons. Read the first floor whose firing count is zero as the floor that silences
ordinary traffic, and the floors below it as the price of a lower bar. Nothing here writes back into
PROGRAM.md — that edit is a decision, made by a human looking at this table.
"""

from __future__ import annotations

import argparse
import json
import sys
from collections import Counter
from dataclasses import asdict, replace
from pathlib import Path

from . import bridge, corpus, windows
from .corpus import COST, TOOL_DURATION, TURN_DURATION

#: The candidate floors, in log units. Spanning ~1% to ~2× because the floor's job is to be
#: PICKED from a measured curve rather than defended from a prior: 0.18 ("about 1.2×") is where the
#: guess sits, and a run that only checked near it could never discover that the honest answer is
#: three times higher.
DEFAULT_FLOORS = (0.02, 0.05, 0.08, 0.10, 0.14, 0.18, 0.22, 0.28, 0.35, 0.45, 0.60, 0.80)

#: Comparisons below which this run declines to name a floor.
#:
#: Zero false positives over `c` comparisons does not say the rate is zero; by the rule of three it
#: says the rate is under about 3/c at 95% confidence. At 20 comparisons that bound is 150 per 1,000
#: — every candidate floor is "clean" and none is distinguishable from any other, so the curve's
#: lowest point wins by an accident of sample size and a 2% floor gets written into the catalog.
#: 1,000 is where the bound tightens to 3 per 1k, which is behaviour drift's own alert budget
#: (PROGRAM.md §12.5) and the closest thing this repo has to a stated definition of quiet enough.
#:
#: This is a reporting gate, not a filter: every floor's count is printed either way. What it stops
#: is the one line a reader acts on claiming more than the corpus supports.
MIN_COMPARISONS_FOR_A_FLOOR = 1_000

SYNTHETIC_BANNER = """
!! SYNTHETIC CORPUS — THIS RUN CANNOT SET AN OPERATING POINT !!
   Every number below is a property of the generator in corpus.py, not of production traffic.
   Use it to check that the harness runs; export a real corpus (README, "Getting a corpus") before
   reading a floor off it.
"""


def measured_rows(turns: list[corpus.Turn], measure: str) -> list[corpus.Sample]:
    return corpus.samples(turns, measure)


def split_in_half(rows: list[corpus.Sample], config: windows.Config) -> tuple[list[bridge.Sketch], list[bridge.Job]]:
    """PROGRAM.md §12.2 read literally: each bucket's own traffic, cut chronologically in two.

    One comparison per bucket, and the cheapest possible statement of the null hypothesis — the two
    halves are the same population, so anything that fires is wrong. It answers a narrower question
    than `--mode replay` (which replays the real window mechanics and makes many more comparisons),
    and it answers it without any of this harness's window bookkeeping standing between the corpus
    and the detector. When the two modes disagree, the windowing is what to look at.
    """
    sketches: list[bridge.Sketch] = []
    jobs: list[bridge.Job] = []
    for bucket_key, bucket_rows in corpus.by_bucket(rows).items():
        if len(bucket_rows) < 2 * config.min_sample:
            continue
        mid = len(bucket_rows) // 2
        first, second = bucket_rows[:mid], bucket_rows[mid:]
        sketches.append(bridge.Sketch(f"{bucket_key}|a", [r.value for r in first], bins=config.hist_bins))
        sketches.append(bridge.Sketch(f"{bucket_key}|b", [r.value for r in second], bins=config.hist_bins))
        jobs.append(
            bridge.Job(
                id=f"{bucket_key}|split",
                measure=TURN_DURATION,
                reference="pinned",
                ref=f"{bucket_key}|a",
                cur=f"{bucket_key}|b",
                bucket_key=bucket_key,
                min_sample=config.min_sample,
                bins=config.hist_bins,
            )
        )
    return sketches, jobs


def run_measure(
    measure: str, rows: list[corpus.Sample], config: windows.Config, floors: tuple[float, ...], mode: str
) -> dict:
    """One measure's whole floor curve, from one JVM start.

    The sketches are built once and every candidate floor re-decides the same pairs, so the curve is
    a property of one set of comparisons rather than of a dozen separately noisy runs.
    """
    grid = corpus.GRID_OF[measure]
    if mode == "split":
        sketches, base_jobs = split_in_half(rows, config)
        base_jobs = [replace(j, measure=measure) for j in base_jobs]
        for sketch in sketches:
            sketch.grid = grid
        planned = None
    else:
        plan = windows.plan(measure, rows, config)
        sketches, base_jobs, planned = plan.sketches, plan.jobs, plan

    if not base_jobs:
        return {
            "measure": measure,
            "mode": mode,
            "comparisons": 0,
            "note": "no bucket reached min_sample twice — nothing was compared",
            "floors": [],
        }

    jobs = [replace(job, id=f"f{i}|{job.id}", w1_floor=floor) for i, floor in enumerate(floors) for job in base_jobs]
    response = bridge.decide(sketches, jobs)

    curve = []
    for i, floor in enumerate(floors):
        prefix = f"f{i}|"
        decisions = {
            key[len(prefix) :]: replace(value, id=key[len(prefix) :])
            for key, value in response.decisions.items()
            if key.startswith(prefix)
        }
        if planned is not None:
            found = windows.findings_of(planned, decisions)
            fired = len(found)
            causes = len({f.cause_key for f in found})
        else:
            fired = sum(1 for d in decisions.values() if d.fired)
            causes = len({d.cause_key for d in decisions.values() if d.fired})
        comparable = sum(1 for d in decisions.values() if d.silence != "NO_REFERENCE")
        curve.append(
            {
                "w1_floor": floor,
                "ratio": round(2.718281828459045**floor, 3),
                "findings": fired,
                "distinct_causes": causes,
                "comparisons": comparable,
                "per_1000_comparisons": round(1000 * fired / comparable, 2) if comparable else None,
            }
        )

    # The per-run facts (how many windows closed, why the silent ones were silent) are read off the
    # first floor's decisions: they are the same comparisons at every floor, and only the verdict moves.
    first = {k: v for k, v in response.decisions.items() if k.startswith("f0|")}
    return {
        "measure": measure,
        "mode": mode,
        "readings": len(rows),
        "buckets": len(corpus.by_bucket(rows)),
        # In split mode a "window" is one half of a bucket, so there are two per comparison.
        "windows_closed": len(planned.planned) if planned is not None else 2 * len(base_jobs),
        "comparisons": sum(1 for d in first.values() if d.silence != "NO_REFERENCE"),
        "silence": dict(Counter(d.silence or "FIRED" for d in first.values())),
        # PLAN.md §11: a non-empty overflow counter is the signal that a grid's range is wrong for
        # this corpus, and it is invisible in the decisions themselves.
        "edge_samples": {
            "underflow": sum(e["underflow"] for e in response.edges.values()),
            "overflow": sum(e["overflow"] for e in response.edges.values()),
            "total": sum(e["count"] for e in response.edges.values()),
        },
        "floors": curve,
    }


def report(results: list[dict], synthetic: bool) -> str:
    lines: list[str] = []
    if synthetic:
        lines.append(SYNTHETIC_BANNER.strip())
        lines.append("")
    for result in results:
        lines.append(f"== {result['measure']}  ({result['mode']} mode)")
        if not result["floors"]:
            lines.append(f"   {result.get('note', 'nothing compared')}")
            lines.append("")
            continue
        edges = result["edge_samples"]
        lines.append(
            f"   readings={result['readings']}  buckets={result['buckets']}  "
            f"windows_closed={result['windows_closed']}  comparisons={result['comparisons']}"
        )
        lines.append(f"   silence={result['silence']}")
        lines.append(
            f"   grid edges: underflow={edges['underflow']} overflow={edges['overflow']} of {edges['total']} samples"
            + ("   <-- range is wrong for this corpus" if edges["overflow"] or edges["underflow"] else "")
        )
        lines.append("   w1_floor    ~ratio   findings   causes   per 1k comparisons")
        for row in result["floors"]:
            per_k = "-" if row["per_1000_comparisons"] is None else f"{row['per_1000_comparisons']:.2f}"
            lines.append(
                f"   {row['w1_floor']:<11.2f} {row['ratio']:<7.2f} {row['findings']:<10d} "
                f"{row['distinct_causes']:<8d} {per_k}"
            )
        clean = [row for row in result["floors"] if row["findings"] == 0]
        if clean:
            comparisons = result["comparisons"]
            # Zero findings over `c` comparisons does not bound the false-positive rate below
            # 0 — it bounds it below roughly 3/c at 95% confidence (the rule of three). Stated
            # because the line under it is the one somebody will act on, and a floor chosen off
            # a handful of comparisons is the same guess it was sent here to replace, wearing a
            # measurement's clothes. 1,000 is where the bound reaches behaviour drift's own alert
            # budget of 3 per 1k, which is the closest thing this repo has to a house unit for
            # "quiet enough".
            bound = 3000.0 / comparisons if comparisons else float("inf")
            if comparisons < MIN_COMPARISONS_FOR_A_FLOOR:
                lines.append(
                    f"   -> NOT ENOUGH COMPARISONS to set a floor. {comparisons} of them put the "
                    f"95% upper bound on this detector's false-positive rate at {bound:.1f} per 1k, "
                    f"and no candidate floor can be told apart from any other below it."
                )
                lines.append(
                    f"      {clean[0]['w1_floor']:.2f} (~{clean[0]['ratio']:.2f}x) is the lowest that "
                    f"was quiet — read it as a lead, not as an operating point. Export a longer stretch."
                )
            else:
                lines.append(
                    f"   -> lowest floor with zero false positives on this corpus: {clean[0]['w1_floor']:.2f} "
                    f"(~{clean[0]['ratio']:.2f}x)"
                )
                lines.append(
                    f"      over {comparisons} comparisons — 95% upper bound on the rate at that "
                    f"floor: {bound:.2f} per 1k."
                )
        else:
            lines.append(
                "   -> NO candidate floor silences this corpus. Either the corpus is not null "
                "(a real regression is in it), or the floor has to go above the sweep's top candidate."
            )
        lines.append("")
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--corpus", help="JSONL exported with corpus.TURN_EXPORT_SQL / TOOL_EXPORT_SQL")
    parser.add_argument(
        "--synthetic",
        action="store_true",
        help="run against generated traffic instead. Smoke-test only — cannot set an operating point.",
    )
    parser.add_argument("--mode", choices=("replay", "split"), default="replay")
    parser.add_argument("--min-sample", type=int, default=windows.Config.min_sample)
    parser.add_argument("--window-target-count", type=int, default=windows.Config.window_target_count)
    parser.add_argument("--window-max-hours", type=int, default=windows.Config.window_max_hours)
    parser.add_argument("--out", default="data/metric_drift/null_case.json")
    args = parser.parse_args(argv)

    if not args.corpus and not args.synthetic:
        parser.error("pass --corpus <file> (see the README) or --synthetic to smoke-test the harness")

    turns = corpus.synthetic() if args.synthetic else corpus.load_turns_jsonl(args.corpus)
    config = windows.Config(
        window_target_count=args.window_target_count,
        window_max_hours=args.window_max_hours,
        min_sample=args.min_sample,
    )

    results = []
    for measure in (TURN_DURATION, TOOL_DURATION, COST):
        rows = measured_rows(turns, measure)
        if not rows:
            results.append({"measure": measure, "mode": args.mode, "comparisons": 0, "floors": [],
                            "note": "no readings — this measure abstained on the whole corpus"})
            continue
        # Each measure is counted on its own. Suppression (§6.1) can only ever MERGE two firings into
        # one, so per-measure counts are the conservative reading of a false-positive rate — and the
        # conservative reading is the one a floor should be set from.
        results.append(run_measure(measure, rows, config, DEFAULT_FLOORS, args.mode))

    print(report(results, args.synthetic))
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(
        json.dumps(
            {
                "run": "null_case",
                "mode": args.mode,
                "corpus": "SYNTHETIC" if args.synthetic else args.corpus,
                "synthetic": args.synthetic,
                "config": asdict(config),
                "results": results,
            },
            indent=2,
        )
    )
    print(f"wrote {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
