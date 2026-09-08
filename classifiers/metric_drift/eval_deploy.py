# SPDX-License-Identifier: Apache-2.0
"""Run 3 — deploy replay: a known past regression fires, on the right deploy. PLAN.md §9.3.

The first two runs answer "does it notice" and "how often is it wrong". This one answers the question
a human actually asks in front of a finding: **since when?** A finding that says a call site got 1.4×
slower and points at the wrong deploy sends somebody to read the wrong diff, which is worse than
silence because it costs the same investigation and ends in the wrong place.

    # a regression you already know about
    uv run python -m metric_drift.eval_deploy --corpus data/metric_drift/turns.jsonl \\
        --bucket discover-sales-prospects --deploy pv_01J...

    # no known regression to hand: break the corpus at a real deploy boundary and replay that
    uv run python -m metric_drift.eval_deploy --corpus data/metric_drift/turns.jsonl \\
        --bucket discover-sales-prospects --deploy pv_01J... --simulate 1.5

What is checked, and why each is separate:

- **fired at all** — the regression is visible to the detector on this corpus, at this floor.
- **`since_version_id`** — the deploy the shift is measured SINCE. For a finding against the pinned
  reference that is the version the reference was captured under, which is the last state anybody
  agreed was normal; for one against the previous window it is the closing window's own version. Both
  are correct answers to different questions, and the run prints which reference fired so the number
  can be read.
- **onset** — the close time of the first window that showed the shift, which is what
  `CaseDetection.onsetAt` carries. It is not when the sweep noticed, and the gap between the two is
  the lag run 2 reports.
"""

from __future__ import annotations

import argparse
import json
import sys
from dataclasses import asdict
from pathlib import Path

from . import corpus, inject, windows
from .corpus import COST, TOOL_DURATION, TURN_DURATION


def deploy_timeline(turns: list[corpus.Turn]) -> list[tuple[str, str]]:
    """Every version in the corpus with the first event time it appears at, in order.

    Read off the traffic rather than from a deploy table because the corpus is the only thing this
    harness has, and because what matters to a finding is when a version started SERVING, not when
    somebody clicked deploy.
    """
    first: dict[str, str] = {}
    for turn in turns:
        version = turn.project_version_id
        if version is None:
            continue
        if version not in first or turn.event_at < first[version]:
            first[version] = turn.event_at
    return sorted(first.items(), key=lambda item: item[1])


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--corpus", help="JSONL exported with corpus.TURN_EXPORT_SQL / TOOL_EXPORT_SQL")
    parser.add_argument("--synthetic", action="store_true", help="generated traffic; smoke-test only")
    parser.add_argument("--bucket", required=True, help="the call site (or tool bucket) that regressed")
    parser.add_argument("--deploy", help="project_version_id the regression shipped in; defaults to the second version")
    parser.add_argument("--measure", choices=(TURN_DURATION, TOOL_DURATION, COST), default=TURN_DURATION)
    parser.add_argument(
        "--simulate",
        type=float,
        help="no known regression? multiply the bucket's durations by this from the deploy boundary. "
        "The run is then a check of the ATTRIBUTION machinery, not evidence that a real regression "
        "would have been caught — it is labelled as such in the output.",
    )
    parser.add_argument("--w1-floor", type=float, default=windows.Config.w1_floor)
    parser.add_argument("--min-sample", type=int, default=windows.Config.min_sample)
    parser.add_argument("--window-target-count", type=int, default=windows.Config.window_target_count)
    parser.add_argument("--out", default="data/metric_drift/deploy_replay.json")
    args = parser.parse_args(argv)
    if not args.corpus and not args.synthetic:
        parser.error("pass --corpus <file> or --synthetic")

    turns = (
        corpus.synthetic(versions=("pv_synthetic_1", "pv_synthetic_2", "pv_synthetic_3"))
        if args.synthetic
        else corpus.load_turns_jsonl(args.corpus)
    )
    timeline = deploy_timeline(turns)
    if len(timeline) < 2:
        print(
            "the corpus carries fewer than two project_version_ids, so there is no deploy boundary to "
            "attribute a shift to. Export a window of traffic that spans at least one deploy."
        )
        return 1

    deploy = args.deploy or timeline[1][0]
    versions = [v for v, _ in timeline]
    if deploy not in versions:
        print(f"{deploy} is not in the corpus. Versions present, in order: {', '.join(versions)}")
        return 1
    boundary = dict(timeline)[deploy]
    previous_version = versions[versions.index(deploy) - 1] if versions.index(deploy) > 0 else None

    if args.simulate:
        # The onset is the deploy boundary itself: a regression that shipped with a deploy starts
        # when that version starts serving, which is the whole thing being attributed.
        affected = [t for t in turns if t.call_site_id == args.bucket]
        before = sum(1 for t in affected if t.event_at < boundary)
        fraction = before / len(affected) if affected else 0.5
        injection = inject.scale_duration(turns, args.bucket, args.simulate, fraction)
        turns = injection.turns

    config = windows.Config(
        window_target_count=args.window_target_count,
        min_sample=args.min_sample,
        w1_floor=args.w1_floor,
    )
    replay = windows.replay(args.measure, corpus.samples(turns, args.measure), config)
    hits = sorted(
        (f for f in replay.findings if f.bucket_key == args.bucket),
        key=lambda f: f.window.index,
    )
    after = [f for f in hits if f.window.closed_at >= boundary]

    result = {
        "run": "deploy_replay",
        "corpus": "SYNTHETIC" if args.synthetic else args.corpus,
        "synthetic": args.synthetic,
        "simulated_regression": args.simulate,
        "measure": args.measure,
        "bucket": args.bucket,
        "deploy": deploy,
        "deploy_first_seen_at": boundary,
        "version_before_deploy": previous_version,
        "versions_in_corpus": versions,
        "config": asdict(config),
        "windows_closed": sum(1 for w in replay.closes if w.bucket_key == args.bucket),
        "findings": [
            {
                "cause_key": f.cause_key,
                "title": f.decision.title,
                "reference": f.decision.reference,
                "ratio": round(f.decision.ratio, 3),
                "w1_log": round(f.decision.w1_log, 4),
                "since_version_id": f.since_version_id,
                # CaseDetection.onsetAt: when the spell began, not when the sweep noticed.
                "onset_at": f.window.closed_at,
                "window_opened_at": f.window.opened_at,
                "window_index": f.window.index,
                "after_deploy": f.window.closed_at >= boundary,
            }
            for f in hits
        ],
    }

    print(report(result, after, previous_version))
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(result, indent=2))
    print(f"wrote {out}")
    return 0 if after else 1


def report(result: dict, after: list[windows.Finding], previous_version: str | None) -> str:
    lines = []
    if result["synthetic"]:
        lines.append("!! SYNTHETIC CORPUS — this checks the attribution machinery, not a real regression.")
    if result["simulated_regression"]:
        lines.append(
            f"!! SIMULATED REGRESSION (x{result['simulated_regression']} from the deploy boundary) — "
            "the corpus was modified, so 'it fired' is by construction. What is being tested is WHERE it points."
        )
    lines.append("")
    lines.append(f"bucket   {result['bucket']}   measure {result['measure']}")
    lines.append(f"deploy   {result['deploy']}  first served {result['deploy_first_seen_at']}")
    lines.append(f"before   {previous_version or '(none — this is the first version in the corpus)'}")
    lines.append(f"windows closed on this bucket: {result['windows_closed']}")
    lines.append("")

    if not result["findings"]:
        lines.append("nothing fired on this bucket at all. Either the shift is under the floor, or the")
        lines.append("bucket never reached min_sample twice — check the null run's silence counts first.")
        return "\n".join(lines)

    for finding in result["findings"]:
        marker = "after deploy " if finding["after_deploy"] else "BEFORE deploy"
        lines.append(f"  [{marker}] {finding['cause_key']}")
        lines.append(f"      {finding['title']}   (W1 {finding['w1_log']}, vs the {finding['reference']} window)")
        lines.append(f"      onset {finding['onset_at']}   since_version_id {finding['since_version_id']}")

    lines.append("")
    if not after:
        lines.append("VERDICT: the regression did NOT produce a finding after the deploy.")
        return "\n".join(lines)

    first = after[0]
    lines.append(f"VERDICT: fired, first at {first.window.closed_at}, pointing at {first.since_version_id}.")
    if first.decision.reference == "pinned" and previous_version and first.since_version_id == previous_version:
        lines.append("         since_version_id names the version the reference was pinned under — the last")
        lines.append("         state anybody agreed was normal — which is what 'since' means here.")
    elif first.decision.reference == "pinned":
        lines.append(
            f"         NOTE: the reference was pinned under {first.since_version_id}, not {previous_version}. "
            "That is correct when nothing re-pinned in between (this replay never absorbs), but it means "
            "the diff to read spans every deploy from there to the regression."
        )
    else:
        lines.append("         This fired against the PREVIOUS window, so since_version_id is the closing")
        lines.append("         window's own version: the shift is 'since last window', not 'since a deploy'.")
    return "\n".join(lines)


if __name__ == "__main__":
    sys.exit(main())
