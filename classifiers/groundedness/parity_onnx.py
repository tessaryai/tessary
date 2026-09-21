# SPDX-License-Identifier: Apache-2.0
"""Does the ONNX export score the same as the torch checkpoint it came from?

WHY THIS IS NOT CEREMONY. Every recall figure in this module was measured in torch. The service
serves ONNX. If the two disagree, those figures describe a model nobody runs — and this is not
hypothetical: `facebook/bart-large-mnli` in torch and `Xenova/bart-large-mnli` (an ONNX conversion
of that same checkpoint) differ by up to 0.297 on identical inputs, which is enough to flip a
fire/quiet decision.

So this compares torch against our own export on a fixed sample of the eval set, reports the
distribution of the difference rather than one summary number, and counts how often the two
runtimes would make a DIFFERENT detection decision at the measured operating point. The last count
is the one that matters: a mean difference of 0.01 is harmless, and a 0.01 difference that straddles
the threshold on 5% of observations is not.

It also writes a fixture of (premise, claim) -> torch score, which is what a serving-side parity
test asserts against — the shape `conformance_parity.json` and `embed.smoke.test.js` already
establish for the `/embed` path.

Run:
    uv run --extra train python -m groundedness.parity_onnx
"""

from __future__ import annotations

import argparse
import json
import statistics
from pathlib import Path

from .eval_ablations import ARMS
from .export_onnx import ARTIFACTS, DEFAULT_CHECKPOINT
from .local_scorer import LocalNliPairScorer

FIXTURE = Path("data/groundedness/onnx_parity_fixture.json")

#: The operating point the authored/generated runs land near, on `unsupported = 1 - support`. Used
#: only to count decision flips; nothing here re-derives a threshold.
THRESHOLD = 0.75


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--checkpoint", default=DEFAULT_CHECKPOINT)
    ap.add_argument("--onnx", default=None, help="export dir (default: artifacts/<model name>)")
    ap.add_argument("--corpus", default="generated")
    ap.add_argument("--samples", type=int, default=40)
    ap.add_argument("--tiling", default="doc", choices=("byte", "doc", "para"))
    ap.add_argument("--out", default=str(FIXTURE))
    args = ap.parse_args()

    from .eval_ablations import load_corpus

    onnx_dir = Path(args.onnx) if args.onnx else ARTIFACTS / args.checkpoint.split("/")[-1]
    int8_dir = Path(f"{onnx_dir}-int8")

    pairs, _, provenance = load_corpus(args.corpus)
    sample = pairs[:: max(1, len(pairs) // args.samples)][: args.samples]
    inputs = [(p.premise, p.claim) for p in sample]
    docs = [p.documents for p in sample]

    cfg = next(c for a, c in ARMS.items() if c.tiling == args.tiling and c.reduce == "min" and "pair" not in a)
    print(f"corpus: {args.corpus} — {provenance}")
    print(f"sample: {len(sample)} pairs, tiling={args.tiling}\n")

    runs: dict[str, list[float]] = {}
    for label, path in (("torch", args.checkpoint), ("onnx-fp32", onnx_dir), ("onnx-int8", int8_dir)):
        if label != "torch" and not Path(path).exists():
            print(f"{label:<10} SKIP (no export at {path} — run export_onnx.py)")
            continue
        scorer = LocalNliPairScorer(str(path), cfg, batch=8)
        runs[label] = scorer.score_pairs(inputs, docs)
        print(f"{label:<10} scored {len(runs[label])} pairs as {scorer.runtime}")

    reference = runs["torch"]
    print(f"\n  {'runtime':<11} {'max |diff|':>11} {'mean |diff|':>12} {'p95 |diff|':>11} {'decision flips':>15}")
    print("  " + "-" * 66)
    for label, scores in runs.items():
        if label == "torch":
            continue
        diffs = sorted(abs(a - b) for a, b in zip(reference, scores))
        flips = sum(
            1 for a, b in zip(reference, scores) if ((1 - a) >= THRESHOLD) != ((1 - b) >= THRESHOLD)
        )
        p95 = diffs[min(len(diffs) - 1, int(0.95 * len(diffs)))]
        print(
            f"  {label:<11} {max(diffs):>11.4f} {statistics.fmean(diffs):>12.4f} {p95:>11.4f} "
            f"{flips:>11}/{len(diffs)}"
        )

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(
        json.dumps(
            {
                "checkpoint": args.checkpoint,
                "tiling": args.tiling,
                "threshold": THRESHOLD,
                "note": (
                    "Reference scores are SUPPORT (1 - P(contradiction)) from the torch checkpoint, "
                    "under the tiling named above. A serving-side parity test asserts its own "
                    "scores against these."
                ),
                "cases": [
                    {"claim": p.claim, "documents": list(p.documents), "support": s, "slices": p.slices}
                    for p, s in zip(sample, reference)
                ],
            },
            indent=2,
            ensure_ascii=False,
        ),
        encoding="utf-8",
    )
    print(f"\nwrote fixture: {out} ({len(sample)} cases)")
    print(
        "  A decision flip is what decides whether a runtime swap is safe. Scores differing in the\n"
        "  fourth decimal are noise; scores differing across the threshold are a different detector."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
