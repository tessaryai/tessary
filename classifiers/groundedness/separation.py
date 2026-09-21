# SPDX-License-Identifier: Apache-2.0
"""Per-window separation: can a checkpoint tell the contradicted document from its neighbours?

WHY THIS IS THE SCREEN. Document-aligned tiling scores every retrieved document separately and
reduces by max-contradiction, so a checkpoint needs to rank the ONE document a claim contradicts
above every other document retrieved in the same conversation. Overall NLI quality does not predict
that; `microsoft/deberta-large-mnli` scores 0.120 under byte tiling and 0.022 under document
alignment precisely because each extra window is another chance to fire wrongly. Separation is the
property, and this measures it directly instead of inferring it from a full ablation that costs
hours.

WHY IT REPLACES THE ONE-CLAIM PROBE. Screening started on a single hand-built claim against eight
distractors, which is an anecdote: one unlucky filler moves the margin by half. This runs every
claim in a corpus against every document in its premise pool and reports the distribution, so a
checkpoint is judged on hundreds of comparisons rather than one.

THE HEADLINE IS `hit_rate`, NOT THE MEAN MARGIN. What document-aligned scoring needs is that the
source outranks ALL distractors — a single distractor above it fires a false positive no threshold
can undo, because max-contradiction takes the worst one. A checkpoint with a good average and
occasional inversions is a checkpoint that fires wrongly at a rate its average hides.

Run:
    uv run --extra train python -m groundedness.separation --checkpoints <a> <b>
"""

from __future__ import annotations

import argparse
import statistics

from . import results
from .labels import POSITIVE_KINDS
from .local_scorer import Config, LocalNliPairScorer


def evaluate(checkpoint: str, claims: list, docs_by_id: dict, pool: list, limit: int) -> dict:
    """Score every positive claim against its source document and against distractors."""
    scorer = LocalNliPairScorer(checkpoint, Config(reduce="min", tiling="byte"), batch=8)

    margins: list[float] = []
    hits = 0
    worst_examples: list[tuple[float, str, str]] = []
    for claim in claims[:limit]:
        source = docs_by_id[claim["source"]]
        distractors = [d for d in pool if d["domain"] == source["domain"] and d["id"] != source["id"]]
        if not distractors:
            continue
        # `support` is what the service returns; contradiction is 1 - support. One document per
        # call, which is exactly what a document-aligned serving path would send.
        texts = [source["text"], *[d["text"] for d in distractors]]
        support = scorer.score_pairs([(t, claim["text"]) for t in texts])
        contradiction = [1.0 - s for s in support]

        true_score, distractor_scores = contradiction[0], contradiction[1:]
        worst = max(distractor_scores)
        margins.append(true_score - worst)
        if true_score > worst:
            hits += 1
        else:
            worst_examples.append((true_score - worst, claim["text"], claim["source"]))

    return {
        "n": len(margins),
        "hit_rate": hits / len(margins) if margins else 0.0,
        "mean_margin": statistics.fmean(margins) if margins else 0.0,
        "median_margin": statistics.median(margins) if margins else 0.0,
        "p10_margin": sorted(margins)[max(0, int(0.10 * len(margins)) - 1)] if margins else 0.0,
        "worst": sorted(worst_examples)[:3],
    }


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--checkpoints", nargs="+", required=True)
    ap.add_argument("--limit", type=int, default=60, help="positive claims to score per checkpoint")
    args = ap.parse_args()

    from .generated_pairs import load

    _, corpus = load()
    docs_by_id = {d["id"]: d for d in corpus["documents"]}
    positives = [c for c in corpus["claims"] if c["kind"] in POSITIVE_KINDS]
    print(f"corpus: {len(corpus['claims'])} claims ({len(positives)} positive), "
          f"{len(corpus['documents'])} documents\n")

    print(f"  {'checkpoint':<52} {'n':>4} {'hit rate':>9} {'mean':>8} {'median':>8} {'p10':>8}")
    print("  " + "-" * 94)
    for ckpt in args.checkpoints:
        try:
            r = evaluate(ckpt, positives, docs_by_id, corpus["documents"], args.limit)
        except Exception as exc:
            print(f"  {ckpt[:52]:<52} SKIP ({type(exc).__name__}: {str(exc)[:40]})")
            continue
        results.record(results.Result(
            name="separation",
            checkpoint=ckpt,
            corpus="generated",
            n_pos=r["n"],
            config={"limit": args.limit, "tiling": "per-document"},
            extra={"hit_rate": round(r["hit_rate"], 3), "mean_margin": round(r["mean_margin"], 3),
                   "median_margin": round(r["median_margin"], 3), "p10": round(r["p10_margin"], 3)},
            note="hit_rate is the screen; no precision/recall shape at this grain",
        ))
        name = ckpt if len(ckpt) <= 52 else "..." + ckpt[-49:]
        print(
            f"  {name:<52} {r['n']:>4} {r['hit_rate']:>9.3f} {r['mean_margin']:>8.3f} "
            f"{r['median_margin']:>8.3f} {r['p10_margin']:>8.3f}"
        )

    print(
        "\n  hit rate = fraction of claims whose contradicted document outranks EVERY distractor.\n"
        "  That is what document-aligned scoring needs; a max-contradiction reduction takes the\n"
        "  worst distractor, so an inversion anywhere is a false fire no threshold can undo.\n"
        "  p10 is the 10th-percentile margin: how bad the hard cases are, which the mean hides."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
