# SPDX-License-Identifier: Apache-2.0
"""Compare two arms on the SAME claims, which is what the data actually supports.

WHY THE INDEPENDENT INTERVALS WERE THE WRONG TEST. Every arm in `eval_ablations` scores the same
corpus, so two arms differing by 0.042 recall with individual 95% intervals of +/-0.085 look
indistinguishable — and that reading throws away the pairing. What matters is the variance of the
DIFFERENCE, and when two configurations agree on most claims and differ on a few, that variance is
far smaller than either arm's own.

TWO SOURCES OF DEPENDENCE ARE HANDLED, AND BOTH MATTER.

1. Arms are paired on claims. Compared arm-by-arm, the shared difficulty of the corpus is noise;
   compared claim-by-claim, it cancels.
2. Pairs are NOT independent of each other: every claim appears once per premise shape, so 1,370
   pairs carry only 274 claims of information. The bootstrap therefore resamples CLAIMS and takes
   each claim's pairs with it (a cluster bootstrap). Resampling pairs directly would treat five
   views of one claim as five independent observations and report an interval far too tight —
   precisely the error that makes a difference look significant when it is not.

McNemar's test is reported alongside as the discordance count, because it is the quantity a reader
can check by hand: of the claims where the two arms disagree, how lopsided is the split?
"""

from __future__ import annotations

import argparse
import json
from collections import defaultdict
from pathlib import Path

#: Pairs are laid out claim-major with one entry per premise shape, so this recovers the claim a
#: pair belongs to. It is the clustering unit for the bootstrap.
SHAPES_PER_CLAIM = 5


def load(path: Path) -> dict[str, list[dict]]:
    data = json.loads(path.read_text(encoding="utf-8"))
    by_arm: dict[str, list[dict]] = defaultdict(list)
    for row in data["predictions"]:
        by_arm[row["arm"]].append(row)
    return by_arm


def recall_of(rows: list[dict]) -> float:
    pos = [r for r in rows if r["label"] == 1]
    return sum(1 for r in pos if r["fired"]) / len(pos) if pos else 0.0


def cluster_bootstrap(a: list[dict], b: list[dict], rounds: int = 2000, seed: int = 17):
    """Resample CLAIMS with replacement; report the distribution of (recall_a - recall_b)."""
    import random

    rng = random.Random(seed)
    by_claim_a: dict[int, list[dict]] = defaultdict(list)
    by_claim_b: dict[int, list[dict]] = defaultdict(list)
    for r in a:
        by_claim_a[r.get("cluster", r["pair_index"] // SHAPES_PER_CLAIM)].append(r)
    for r in b:
        by_claim_b[r.get("cluster", r["pair_index"] // SHAPES_PER_CLAIM)].append(r)
    # Only claims with positives contribute to a recall difference.
    claims = [c for c in by_claim_a if any(r["label"] == 1 for r in by_claim_a[c])]

    diffs = []
    for _ in range(rounds):
        picked = [claims[rng.randrange(len(claims))] for _ in claims]
        ra = recall_of([r for c in picked for r in by_claim_a[c]])
        rb = recall_of([r for c in picked for r in by_claim_b[c]])
        diffs.append(ra - rb)
    diffs.sort()
    lo = diffs[int(0.025 * len(diffs))]
    hi = diffs[int(0.975 * len(diffs)) - 1]
    wins = sum(1 for d in diffs if d > 0) / len(diffs)
    return sum(diffs) / len(diffs), lo, hi, wins, len(claims)


def mcnemar(a: list[dict], b: list[dict]) -> tuple[int, int]:
    """Discordant positive pairs: (a fired & b did not, b fired & a did not)."""
    by_idx_b = {r["pair_index"]: r for r in b}
    a_only = b_only = 0
    for r in a:
        if r["label"] != 1:
            continue
        other = by_idx_b.get(r["pair_index"])
        if other is None:
            continue
        if r["fired"] and not other["fired"]:
            a_only += 1
        elif other["fired"] and not r["fired"]:
            b_only += 1
    return a_only, b_only


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--dump", default="data/groundedness/preds_decompose.json")
    ap.add_argument("--rounds", type=int, default=2000)
    args = ap.parse_args()

    by_arm = load(Path(args.dump))
    names = list(by_arm)
    if len(names) < 2:
        print(f"need two arms in {args.dump}, found {names}")
        return 1

    print(f"arms: {names[0]!r} vs {names[1]!r}")
    a, b = by_arm[names[0]], by_arm[names[1]]
    print(f"  recall {names[0]}: {recall_of(a):.3f}")
    print(f"  recall {names[1]}: {recall_of(b):.3f}\n")

    mean, lo, hi, wins, n_claims = cluster_bootstrap(a, b, args.rounds)
    a_only, b_only = mcnemar(a, b)
    print(f"  paired difference (cluster bootstrap over {n_claims} positive claims, {args.rounds} rounds):")
    print(f"    mean {mean:+.3f}   95% CI [{lo:+.3f}, {hi:+.3f}]   P(A better) = {wins:.1%}")
    print(f"  McNemar discordance on positive pairs: {names[0]} only {a_only}, {names[1]} only {b_only}")
    verdict = (
        "the difference is real at 95%" if lo > 0 or hi < 0
        else "the interval spans zero: NOT distinguishable on this corpus"
    )
    print(f"\n  {verdict}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
