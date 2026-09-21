# SPDX-License-Identifier: Apache-2.0
"""Recall at fixed FP with the threshold chosen on DIFFERENT claims than it is scored on.

THE BIAS THIS REMOVES. `framework.metrics.operating_point_at_fixed_fp` picks the lowest threshold
whose false-positive rate stays under the ceiling — on the same set it then reports recall from. With
~825 negative pairs and a 2% ceiling, that threshold is tuned to admit exactly the 16 false fires the
test data happens to permit, and every recall figure in `experiments.json` inherits that optimism.
It affects our model and the NC reference equally, so comparisons between arms were fair; the
absolute numbers, and every "clears the gate" claim, were not.

HOW. K-fold over CLAIMS (not pairs — five premise shapes of one claim are one unit of evidence). For
each fold the threshold is fitted on the other folds' negatives, then applied to this fold. The
held-out decisions are pooled and recall / FP rate are read off the pool. Nothing here has seen its
own threshold.

BOTH NUMBERS ARE PRINTED. The in-sample figure is what was reported before; the gap between the two
is the size of the optimism, which is worth knowing on its own.
"""

from __future__ import annotations

import argparse
import json
from collections import defaultdict
from pathlib import Path

from framework.metrics import operating_point_at_fixed_fp

from .labels import GATE
from .paired import SHAPES_PER_CLAIM


def cv_recall(rows: list[dict], k: int = 5, max_fp: float = GATE.max_fp, seed: int = 17,
              guard: float = 0.0):
    """`guard` is subtracted from `max_fp` when FITTING each fold's threshold, so the pooled held-out
    false-positive rate lands under `max_fp` rather than straddling it: a threshold fitted at exactly
    2% on four folds gives 1.9-2.1% on the fifth depending on which 28 negatives it holds, and a gate
    that reads "<= 2%" must not pass or fail on that coin. The reported fp rate is the held-out one."""
    import random

    by_claim: dict[int, list[dict]] = defaultdict(list)
    for r in rows:
        by_claim[r.get("cluster", r["pair_index"] // SHAPES_PER_CLAIM)].append(r)
    claims = sorted(by_claim)
    random.Random(seed).shuffle(claims)
    folds = [claims[i::k] for i in range(k)]

    held_labels: list[int] = []
    held_fired: list[bool] = []
    thresholds: list[float] = []
    for i, test_claims in enumerate(folds):
        train_rows = [r for j, f in enumerate(folds) if j != i for c in f for r in by_claim[c]]
        op = operating_point_at_fixed_fp(
            [r["label"] for r in train_rows], [r["score"] for r in train_rows], max(0.0, max_fp - guard)
        )
        thresholds.append(op.threshold)
        for c in test_claims:
            for r in by_claim[c]:
                held_labels.append(r["label"])
                held_fired.append(r["score"] >= op.threshold)

    pos = [f for f, y in zip(held_fired, held_labels) if y == 1]
    neg = [f for f, y in zip(held_fired, held_labels) if y == 0]
    tp, fp = sum(pos), sum(neg)
    recall = tp / len(pos) if pos else 0.0
    fp_rate = fp / len(neg) if neg else 0.0
    precision = tp / (tp + fp) if (tp + fp) else 0.0
    return {
        "recall": recall, "fp_rate": fp_rate, "precision": precision,
        "f1": 2 * precision * recall / (precision + recall) if (precision + recall) else 0.0,
        "thresholds": thresholds, "n_pos": len(pos), "n_neg": len(neg),
    }


def in_sample(rows: list[dict]) -> dict:
    op = operating_point_at_fixed_fp([r["label"] for r in rows], [r["score"] for r in rows], GATE.max_fp)
    return {"recall": op.recall, "fp_rate": op.fp_rate, "precision": op.precision,
            "threshold": op.threshold}


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--dump", nargs="+", default=["data/groundedness/preds_decompose.json"])
    ap.add_argument("--folds", type=int, default=5)
    args = ap.parse_args()

    from . import results

    print(f"gate: recall >= {GATE.min_recall:.2f} at fp <= {GATE.max_fp:.2f}, "
          f"{args.folds}-fold by claim\n")
    print(f"  {'arm':<38} {'in-sample':>10} {'CV recall':>10} {'CV fp':>7} {'CV prec':>8} "
          f"{'CV F1':>7} {'optimism':>9}  gate")
    print("  " + "-" * 102)
    for dump in args.dump:
        data = json.loads(Path(dump).read_text(encoding="utf-8"))
        by_arm: dict[str, list[dict]] = defaultdict(list)
        for r in data["predictions"]:
            by_arm[(r["checkpoint"].split("/")[-1][:10], r["arm"])].append(r)
        for (ckpt, arm), rows in by_arm.items():
            ins = in_sample(rows)
            cv = cv_recall(rows, args.folds)
            gate = "PASS" if (cv["recall"] >= GATE.min_recall and cv["fp_rate"] <= GATE.max_fp) else "fail"
            label = f"{ckpt}/{arm.replace('doc-aligned + ', '')}"
            print(
                f"  {label[:38]:<38} {ins['recall']:>10.3f} {cv['recall']:>10.3f} {cv['fp_rate']:>7.3f} "
                f"{cv['precision']:>8.3f} {cv['f1']:>7.3f} {ins['recall'] - cv['recall']:>+9.3f}  {gate}"
            )
            results.record(results.Result(
                name=f"cv/{arm}", checkpoint=rows[0]["checkpoint"], corpus=data["corpus"],
                precision=cv["precision"], recall=cv["recall"], fp_rate=cv["fp_rate"],
                threshold=sum(cv["thresholds"]) / len(cv["thresholds"]),
                n_pos=cv["n_pos"], n_neg=cv["n_neg"],
                config={"folds": args.folds, "threshold_selection": "cross-validated by claim"},
                extra={"in_sample_recall": round(ins["recall"], 3),
                       "optimism": round(ins["recall"] - cv["recall"], 3)},
                note="threshold fitted on held-out claims; the honest version of the ablation row",
            ))
    print("\n  optimism = in-sample recall minus cross-validated recall: how much the old numbers")
    print("  gained from fitting the threshold to the set they were scored on.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
