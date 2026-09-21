# SPDX-License-Identifier: Apache-2.0
"""Recall at a fixed false-positive rate, per checkpoint and per configuration.

Every arm is reported at the SAME FP budget (`labels.GATE`) so the numbers compose. The threshold
is chosen by `framework.metrics.operating_point_at_fixed_fp` rather than inherited from the
catalog, because a band fitted to one score distribution says nothing about another.

Run:
    uv run --extra train python -m groundedness.eval_ablations
    uv run --extra train python -m groundedness.eval_ablations --checkpoints facebook/bart-large-mnli
"""

from __future__ import annotations

import argparse
import math

from framework.metrics import OperatingPoint, operating_point_at_fixed_fp

from . import results
from .labels import GATE, POSITIVE_KINDS
from .local_scorer import Config, LocalNliPairScorer
from .pairs import CLAIMS, build_pairs

#: All three-way NLI. The incumbent first so every table reads as a delta against what we serve.
DEFAULT_CHECKPOINTS = (
    "facebook/bart-large-mnli",
    "microsoft/deberta-large-mnli",
    "MoritzLaurer/DeBERTa-v3-large-mnli-fever-anli-ling-wanli",
)

#: The arms. `served_today` is the pre-fix reducer, kept so every run shows the delta rather than
#: asserting it from memory.
ARMS: dict[str, Config] = {
    "served_today (MAX)": Config(reduce="max"),
    "reducer_fixed (MIN)": Config(reduce="min"),
    "reducer_fixed + 8 chunks": Config(reduce="min", max_chunks=8),
    "reducer_fixed + doc-aligned": Config(reduce="min", tiling="doc"),
    "reducer_fixed + para-aligned": Config(reduce="min", tiling="para"),
    # Window size within each document, every window scored (no chunk cap). RAGTruth diagnostic.
    "doc-aligned + window(400)": Config(reduce="min", tiling="doc", window_chars=400),
    "doc-aligned + window(800)": Config(reduce="min", tiling="doc", window_chars=800),
    # Not a tuning knob: this changes how the two strings reach the encoder, and classify.js
    # would have to change for it to be reachable. See Config.input_format.
    "reducer_fixed + pair-encoding": Config(reduce="min", input_format="pair"),
    # Calibration arms: same scores, different decision statistic. Free to evaluate.
    "doc-aligned + margin-norm": Config(reduce="min", tiling="doc", normalize="margin"),
    "doc-aligned + zscore-norm": Config(reduce="min", tiling="doc", normalize="zscore"),
    "doc-aligned + contra-minus-entail": Config(reduce="min", tiling="doc",
                                                normalize="contra_minus_entail"),
    "doc-aligned + decompose": Config(reduce="min", tiling="doc", decompose=True),
    # Stacked: the two levers that moved the number, together. Decomposition splits the claim;
    # the entailment margin sharpens each clause's verdict. They act at different stages, so they
    # may compose — or the entailment margin may only have been recovering what decomposition
    # already fixes, in which case stacking gains nothing and says so.
    "doc-aligned + decompose + cme": Config(reduce="min", tiling="doc", decompose=True,
                                            normalize="contra_minus_entail"),
    "doc-aligned + decompose(40)": Config(reduce="min", tiling="doc", decompose=True,
                                          min_clause_chars=40),
    "doc-aligned + decompose(60)": Config(reduce="min", tiling="doc", decompose=True,
                                          min_clause_chars=60),
}


def wilson(k: int, n: int, z: float = 1.96) -> tuple[float, float]:
    """Wilson score interval. A normal-approximation interval on 24 positives is nonsense at the
    edges (it can exceed 1), and quoting a bare point estimate at this n is worse."""
    if n == 0:
        return (0.0, 0.0)
    p = k / n
    d = 1 + z * z / n
    centre = (p + z * z / (2 * n)) / d
    half = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / d
    return (max(0.0, centre - half), min(1.0, centre + half))


def load_corpus(name: str, path: str | None = None) -> tuple[list, int, str]:
    """`(pairs, positive_claim_count, provenance)` for a named corpus.

    The positive CLAIM count is returned separately because every corpus reuses each claim across
    premise shapes, so it — not the pair count — is the denominator for a recall interval.
    """
    if name == "authored":
        pairs = build_pairs()
        n_pos = sum(1 for c in CLAIMS if c.kind in POSITIVE_KINDS)
        return pairs, n_pos, f"authored in pairs.py, {len(CLAIMS)} claims"
    if name == "generated":
        from .generated_pairs import load

        # An explicit path is a HELD-OUT set: evaluated on its own, never merged. The default
        # merges every verified_corpus*.json, which is the tuning population — held-out files are
        # named so they do not match that glob, and reach here only by being asked for.
        pairs, corpus = load(path)
        n_pos = sum(1 for c in corpus["claims"] if c["kind"] in POSITIVE_KINDS)
        return pairs, n_pos, (
            f"generated by {corpus.get('generator')}, verified by {corpus.get('verifier_model')} "
            f"(kappa {corpus.get('kappa'):.3f}), {len(corpus['claims'])} claims"
        )
    if name == "ragtruth":
        from .ragtruth_pairs import load

        pairs, meta = load()
        n_pos = sum(1 for p in pairs if p.label == 1)
        # n_pos here is positive SENTENCES; clusters (responses) are what CV and paired resample.
        return pairs, n_pos, f"RAGTruth {meta['split']} (MIT, human-labelled), {meta['responses']} Summary/QA responses"
    if name.startswith("ragbench"):
        from .ragbench_pairs import load

        pairs, meta = load(view=name.split("-", 1)[1] if "-" in name else "strict")
        n_pos = sum(1 for p in pairs if p.label == 1)
        return pairs, n_pos, f"RAGBench {meta['corpus']} (CC BY 4.0, GPT-4o-labelled), {meta['responses']} responses"
    raise ValueError(f"unknown corpus {name!r} (authored|generated|ragtruth|ragbench|ragbench-broad)")


def _score_arm(checkpoint: str, config: Config, pairs: list, dump: list | None = None,
               arm_name: str = "", checkpoint_name: str = "") -> tuple[OperatingPoint, dict, int]:
    scorer = LocalNliPairScorer(checkpoint, config)
    inputs = [(p.premise, p.claim) for p in pairs]
    docs = [p.documents for p in pairs]
    support = scorer.score_pairs(inputs, docs)
    # The detector bands on `unsupported = 1 - support`, so that is what the gate must threshold.
    scores = [1.0 - s for s in support]
    labels = [p.label for p in pairs]

    overall = operating_point_at_fixed_fp(labels, scores, GATE.max_fp)

    by_slice: dict[str, dict[str, OperatingPoint]] = {}
    for axis in ("kind", "shape"):
        buckets: dict[str, tuple[list[int], list[float]]] = {}
        for pair, score in zip(pairs, scores):
            key = pair.slices[axis]
            ls, ss = buckets.setdefault(key, ([], []))
            ls.append(pair.label)
            ss.append(score)
        # Score each slice at the OVERALL threshold, not its own — a per-slice threshold would
        # report a detector that cannot exist, since production runs one threshold for all traffic.
        by_slice[axis] = {
            key: _at_threshold(ls, ss, overall.threshold) for key, (ls, ss) in sorted(buckets.items())
        }
    if dump is not None:
        dump.extend(
            {"arm": arm_name, "checkpoint": checkpoint_name, "pair_index": i,
             # The unit the bootstrap and CV folds resample. Generated corpora lay pairs out five
             # premise-shapes per claim; RAGTruth is one pair per sentence with many sentences per
             # response, so it names the response explicitly and the old rule is only a fallback.
             "cluster": p.slices.get("cluster", str(i // 5)),
             "kind": p.slices["kind"], "shape": p.slices["shape"], "domain": p.slices.get("domain"), "source": p.source,
             "label": p.label, "score": sc, "fired": bool(sc >= overall.threshold)}
            for i, (p, sc) in enumerate(zip(pairs, scores))
        )
    return overall, by_slice, scorer.forward_passes(inputs, docs)


def _at_threshold(labels: list[int], scores: list[float], threshold: float) -> OperatingPoint:
    pos = [s for s, y in zip(scores, labels) if y == 1]
    neg = [s for s, y in zip(scores, labels) if y == 0]
    tp = sum(1 for s in pos if s >= threshold)
    fp = sum(1 for s in neg if s >= threshold)
    return OperatingPoint(
        threshold=threshold,
        recall=tp / len(pos) if pos else 0.0,
        fp_rate=fp / len(neg) if neg else 0.0,
        precision=tp / (tp + fp) if (tp + fp) else 0.0,
        n_pos=len(pos),
        n_neg=len(neg),
    )


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--checkpoints", nargs="*", default=list(DEFAULT_CHECKPOINTS))
    ap.add_argument("--arms", nargs="*", default=list(ARMS))
    ap.add_argument("--corpus", default="authored", choices=("authored", "generated", "ragtruth", "ragbench", "ragbench-broad"))
    ap.add_argument("--corpus-path", default=None,
                    help="evaluate ONE corpus file only (a held-out set) instead of the merged "
                         "tuning population")
    ap.add_argument("--dump-predictions", default=None,
                    help="write per-pair scores and decisions here, for PAIRED comparison between "
                         "arms. Independent CIs understate what this data supports: every arm scores "
                         "the SAME claims, so the variance that matters is in the DIFFERENCE, not in "
                         "each arm separately.")
    args = ap.parse_args()

    pairs, n_pos_claims, provenance = load_corpus(args.corpus, args.corpus_path)
    if args.corpus_path:
        provenance = f"HELD-OUT {args.corpus_path} — " + provenance
    dump = [] if args.dump_predictions else None
    print(f"gate:   recall >= {GATE.min_recall:.2f} at fp <= {GATE.max_fp:.2f}")
    print(f"corpus: {args.corpus} — {provenance}")
    print(f"pairs:  {len(pairs)} ({n_pos_claims} positive claims)")
    print(
        "NOTE: pairs reuse claims across premise shapes, so they are NOT independent. The interval "
        f"below uses the {n_pos_claims} positive CLAIMS, not the pair count.\n"
    )

    for checkpoint in args.checkpoints:
        print(f"=== {checkpoint} ===", flush=True)
        header = f"  {'arm':<30} {'recall':>7} {'fp':>6} {'prec':>6} {'thr':>6} {'passes':>7}  {'95% CI (claims)':>18}  gate"
        print(header)
        print("  " + "-" * (len(header) - 2))
        for arm in args.arms:
            config = ARMS[arm]
            try:
                op, by_slice, passes = _score_arm(checkpoint, config, pairs, dump,
                                                  arm_name=arm, checkpoint_name=checkpoint)
            except Exception as exc:  # a checkpoint with no torch weights, a bad label set
                print(f"  {arm:<30} SKIP ({type(exc).__name__}: {str(exc)[:60]})", flush=True)
                continue
            lo, hi = wilson(round(op.recall * n_pos_claims), n_pos_claims)
            verdict = "PASS" if op.passes(GATE) else "fail"
            results.record(results.Result(
                name=f"ablation/{arm}",
                checkpoint=checkpoint,
                corpus=args.corpus,
                precision=op.precision, recall=op.recall, fp_rate=op.fp_rate,
                threshold=op.threshold, n_pos=op.n_pos, n_neg=op.n_neg,
                config={"tiling": config.tiling, "reduce": config.reduce,
                        "max_chunks": config.max_chunks, "input_format": config.input_format,
                        "normalize": config.normalize, "decompose": config.decompose,
                        "min_clause_chars": config.min_clause_chars},
                extra={"passes": passes, "ci_lo": round(lo, 3), "ci_hi": round(hi, 3),
                       "gate": verdict},
            ))
            # flush=True: an arm can take many minutes, and a run piped anywhere but a tty
            # otherwise holds every finished arm in a buffer until exit — so a run that dies
            # loses results it had already computed.
            print(
                f"  {arm:<30} {op.recall:>7.3f} {op.fp_rate:>6.3f} {op.precision:>6.3f} "
                f"{op.threshold:>6.3f} {passes:>7}  [{lo:.2f}, {hi:.2f}]{'':>7}  {verdict}",
                flush=True,
            )
            if arm == args.arms[-1]:
                for axis, buckets in by_slice.items():
                    cells = "  ".join(
                        f"{k}={v.recall:.2f}" if v.n_pos else f"{k}=fp{v.fp_rate:.2f}"
                        for k, v in buckets.items()
                    )
                    print(f"      by {axis}: {cells}")
        print()

    if dump is not None:
        import json as _json
        from pathlib import Path as _Path

        out = _Path(args.dump_predictions)
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(_json.dumps({"corpus": args.corpus, "predictions": dump}, indent=2),
                       encoding="utf-8")
        print(f"wrote {len(dump)} per-pair predictions to {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
