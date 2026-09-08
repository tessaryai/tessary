# SPDX-License-Identifier: Apache-2.0
"""Run 2 — injection: detection rate per operator, and lag in windows. PLAN.md §9.2.

Every operator in `inject.py` is applied to every call site with enough traffic to close windows on
both sides of the onset, and the run reports, per operator:

- **detection rate** — the share of injected buckets that produced a finding after the onset;
- **lag** — how many of that bucket's windows closed after the onset before the first finding. Zero
  means the very first window that saw the regression reported it;
- **collateral** — findings on buckets nobody touched. These are false positives in exactly the null
  run's sense, and they are counted here too because an operator that moves one bucket's traffic can
  thin another's.

    uv run python -m metric_drift.eval_injection --corpus data/metric_drift/turns.jsonl

**Read this run only after the null run.** A detection rate at a floor nobody measured says nothing:
any operator can be detected at a floor low enough, and the whole question is what the same floor
does to unmodified traffic. Pass `--w1-floor` with the value the null run supports.
"""

from __future__ import annotations

import argparse
import json
import sys
from dataclasses import asdict
from pathlib import Path

from . import corpus, inject, windows
from .corpus import COST, TOOL_DURATION, TURN_DURATION

#: The three multiplicative slowdowns PROGRAM.md §12.1 names. 1.2 sits at the guessed floor by
#: construction (ln 1.2 = 0.18), which is why it is the interesting one: it is the operator that
#: reports whether the floor is where the config claims it is.
SCALE_FACTORS = (1.2, 1.5, 2.0)


def eligible_call_sites(turns: list[corpus.Turn], config: windows.Config) -> list[str]:
    """Call sites with enough turns to close a window before the onset and two after it.

    Below that a null result says nothing about the detector — the bucket simply never got compared,
    which is `BELOW_MIN_SAMPLE` and a wait rather than a miss.
    """
    counts: dict[str, int] = {}
    for row in corpus.samples(turns, TURN_DURATION):
        counts[row.bucket_key] = counts.get(row.bucket_key, 0) + 1
    need = 3 * max(config.window_target_count, config.min_sample)
    return sorted(key for key, count in counts.items() if count >= need)


def _post_onset_closes(replay: windows.Replay, bucket_key: str, onset_at: str) -> list[windows.ClosedWindow]:
    return [w for w in replay.closes if w.bucket_key == bucket_key and w.closed_at >= onset_at]


def _first_hit(findings: list[windows.Finding], bucket_key: str, onset_at: str) -> windows.Finding | None:
    hits = [f for f in findings if f.bucket_key == bucket_key and f.window.closed_at >= onset_at]
    return min(hits, key=lambda f: f.window.index) if hits else None


def _lag(post_onset: list[windows.ClosedWindow], finding: windows.Finding | None) -> int | None:
    """Where the firing window sits among the windows that closed after the onset. 0 = the first one."""
    if finding is None or not post_onset:
        return None
    return next((i for i, window in enumerate(post_onset) if window.index == finding.window.index), None)


def run_duration_operator(
    injection: inject.Injection, config: windows.Config
) -> dict:
    """Replay both duration grains, including the real suppression rule, over an injected corpus."""
    result = windows.replay_duration(
        corpus.samples(injection.turns, TURN_DURATION),
        corpus.samples(injection.turns, TOOL_DURATION),
        config,
    )
    turn_hit = _first_hit(result.turn.findings, injection.bucket_key, injection.onset_at)
    emitted_hit = _first_hit(result.emitted, injection.bucket_key, injection.onset_at)
    post = _post_onset_closes(result.turn, injection.bucket_key, injection.onset_at)

    # A tool finding counts as detection too — a slowdown that presents at tool grain IS detected,
    # and §6.1 says the tool row is the one that should carry it.
    tool_hit = next(
        (
            f
            for f in sorted(result.tool.findings, key=lambda f: f.window.index)
            if f.window.closed_at >= injection.onset_at and injection.bucket_key in f.window.call_sites
        ),
        None,
    )
    # Lag is counted at whichever grain noticed first: the question is how long a regression ran
    # before anything said so, not which row said it.
    lag = min(
        (
            value
            for value in (
                _lag(post, turn_hit),
                None
                if tool_hit is None
                else _lag(_post_onset_closes(result.tool, tool_hit.bucket_key, injection.onset_at), tool_hit),
            )
            if value is not None
        ),
        default=None,
    )

    return {
        "detected": turn_hit is not None or tool_hit is not None,
        "lag_windows": lag,
        "windows_after_onset": len(post),
        "turn_finding": _describe(turn_hit),
        "tool_finding": _describe(tool_hit),
        "turn_suppressed_by": turn_hit.suppressed_by if turn_hit else None,
        "turn_covered": turn_hit.covered if turn_hit else None,
        "emitted_for_bucket": _describe(emitted_hit),
        "collateral": _collateral(result.emitted, injection),
    }


def run_cost_operator(injection: inject.Injection, config: windows.Config) -> dict:
    """Cost is replayed alone: the four token buckets are evidence, never findings (§6.1), so one
    real event produces one row here exactly as it does in the product."""
    result = windows.replay(COST, corpus.samples(injection.turns, COST), config)
    hit = _first_hit(result.findings, injection.bucket_key, injection.onset_at)
    post = _post_onset_closes(result, injection.bucket_key, injection.onset_at)
    return {
        "detected": hit is not None,
        "lag_windows": _lag(post, hit),
        "windows_after_onset": len(post),
        "cost_finding": _describe(hit),
        "collateral": _collateral(result.findings, injection),
        "cache_read_ratio_after": 0.0,
    }


def _describe(finding: windows.Finding | None) -> dict | None:
    if finding is None:
        return None
    return {
        "cause_key": finding.cause_key,
        "title": finding.decision.title,
        "ratio": round(finding.decision.ratio, 3),
        "w1_log": round(finding.decision.w1_log, 4),
        "reference": finding.decision.reference,
        "n_ref": finding.decision.n_ref,
        "n_cur": finding.decision.n_cur,
        "since_version_id": finding.since_version_id,
        "window_index": finding.window.index,
    }


def _collateral(findings: list[windows.Finding], injection: inject.Injection) -> list[str]:
    """Findings on traffic the operator did not touch.

    A tool bucket the injected call site dispatches is NOT collateral — a slowdown that presents at
    tool grain is the same event seen at the other grain, which is the whole subject of §6.1. What is
    left is a bucket whose window saw none of the injected call site's traffic, and that is a false
    positive in the null run's sense: an operator that moves one bucket can thin another's window.
    """
    return sorted(
        {
            f.cause_key
            for f in findings
            if f.window.closed_at >= injection.onset_at
            and f.bucket_key != injection.bucket_key
            and injection.bucket_key not in f.window.call_sites
        }
    )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--corpus", help="JSONL exported with corpus.TURN_EXPORT_SQL / TOOL_EXPORT_SQL")
    parser.add_argument("--synthetic", action="store_true", help="generated traffic; smoke-test only")
    parser.add_argument(
        "--w1-floor",
        type=float,
        default=windows.Config.w1_floor,
        help="the operating point to measure at. The default is the UNMEASURED guess in "
        "MetricDriftConfig; pass what the null run supports.",
    )
    parser.add_argument("--min-sample", type=int, default=windows.Config.min_sample)
    parser.add_argument("--window-target-count", type=int, default=windows.Config.window_target_count)
    parser.add_argument("--onset", type=float, default=0.5, help="fraction of the bucket's traffic before the break")
    parser.add_argument("--out", default="data/metric_drift/injection.json")
    args = parser.parse_args(argv)
    if not args.corpus and not args.synthetic:
        parser.error("pass --corpus <file> or --synthetic")

    turns = corpus.synthetic() if args.synthetic else corpus.load_turns_jsonl(args.corpus)
    config = windows.Config(
        window_target_count=args.window_target_count,
        min_sample=args.min_sample,
        w1_floor=args.w1_floor,
    )
    sites = eligible_call_sites(turns, config)
    if not sites:
        print("no call site has enough traffic to close windows either side of an onset — nothing to inject into")
        return 1

    runs: list[dict] = []
    for site in sites:
        for factor in SCALE_FACTORS:
            injection = inject.scale_duration(turns, site, factor, args.onset)
            runs.append(
                {"operator": f"scale_duration x{factor}", "bucket": site, "detail": injection.detail,
                 **run_duration_operator(injection, config)}
            )
        injection = inject.retry_loop(turns, site, onset_fraction=args.onset)
        runs.append(
            {"operator": "retry_loop", "bucket": site, "detail": injection.detail,
             **run_duration_operator(injection, config)}
        )
        injection = inject.collapse_cache(turns, site, onset_fraction=args.onset)
        runs.append(
            {"operator": "collapse_cache", "bucket": site, "detail": injection.detail,
             **run_cost_operator(injection, config)}
        )

    print(report(runs, config, args.synthetic))
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(
        json.dumps(
            {
                "run": "injection",
                "corpus": "SYNTHETIC" if args.synthetic else args.corpus,
                "synthetic": args.synthetic,
                "config": asdict(config),
                "injector_assumptions": {
                    "output_price_ratio": inject.OUTPUT_PRICE_RATIO,
                    "cache_read_discount": inject.CACHE_READ_DISCOUNT,
                },
                "runs": runs,
            },
            indent=2,
        )
    )
    print(f"wrote {out}")
    return 0


def report(runs: list[dict], config: windows.Config, synthetic: bool) -> str:
    lines: list[str] = []
    if synthetic:
        lines.append("!! SYNTHETIC CORPUS — detection rates below describe the generator, not production.")
        lines.append("")
    lines.append(f"w1_floor = {config.w1_floor}  (~{2.718281828459045 ** config.w1_floor:.2f}x)   "
                 f"min_sample = {config.min_sample}   window_target_count = {config.window_target_count}")
    lines.append(
        f"collapse_cache dollars are modelled: output tokens priced {inject.OUTPUT_PRICE_RATIO}x input, "
        f"cache reads at {inject.CACHE_READ_DISCOUNT}x. The cache-read RATIO is exact."
    )
    lines.append("")

    by_operator: dict[str, list[dict]] = {}
    for run in runs:
        by_operator.setdefault(run["operator"], []).append(run)

    lines.append("operator                 detected   lag (windows)   notes")
    for operator, items in by_operator.items():
        detected = [r for r in items if r["detected"]]
        lags = [r["lag_windows"] for r in detected if r["lag_windows"] is not None]
        lag = f"{min(lags)}-{max(lags)}" if lags else "-"
        note = ""
        if any("turn_finding" in r for r in items):
            suppressed = sum(1 for r in items if r.get("turn_suppressed_by"))
            note = f"{suppressed}/{len(items)} turn findings suppressed by a tool (§6.1)"
        lines.append(f"{operator:<24} {len(detected)}/{len(items):<8}  {lag:<15} {note}")

    lines.append("")
    lines.append("per bucket:")
    for run in runs:
        headline = run.get("emitted_for_bucket") or run.get("cost_finding") or run.get("tool_finding")
        title = headline["title"] if headline else "(nothing fired)"
        lines.append(
            f"  {run['operator']:<22} {run['bucket']:<28} "
            f"{'HIT ' if run['detected'] else 'MISS'} lag={run['lag_windows']}  {title}"
        )
        if run.get("turn_suppressed_by"):
            lines.append(
                f"      turn shift suppressed by {run['turn_suppressed_by']} "
                f"(covered {run['turn_covered']:.2f}) — the tool row is the headline, per §6.1"
            )
        if run.get("collateral"):
            lines.append(f"      collateral: {', '.join(run['collateral'])}")
    return "\n".join(lines)


if __name__ == "__main__":
    sys.exit(main())
