# SPDX-License-Identifier: Apache-2.0
"""What one scored observation costs, per tiling, on a starter-sized host.

WHY THIS GATES THE RECALL WORK. `AGENTS.md`: "Anything that scales per-event LLM cost with ingest
volume attacks the product directly." Encoder inference is not LLM spend, but it is per-event spend
and it does scale with ingest, so the same rule applies. Document-aligned tiling buys the recall
that clears the gate and costs about 4x the forward passes of the byte tiler. If that is more than
the ingest path can absorb, the recall result is interesting and unshippable, and it is better to
know that before building a wire change to reach it.

WHAT IS MEASURED. Throughput of the real encoder on this machine, at a thread count matching the
documented starter host (2 vCPU, `docs/self-hosting/deployment.mdx`), then the per-observation and
per-1000-observation cost implied by each tiling's measured window count. Torch rather than ONNX:
production serves ONNX and no ONNX export of the candidate exists yet, so this is an estimate whose
DIRECTION is reliable and whose absolute value must be re-measured after an export. Said here
rather than discovered later.

Run:
    uv run --extra train python -m groundedness.cost
"""

from __future__ import annotations

import argparse
import statistics
import time

from .eval_ablations import ARMS, load_corpus
from .local_scorer import LocalNliPairScorer

#: The starter host in `docs/self-hosting/deployment.mdx`: 2 vCPU, 8 GB. classify-service runs on
#: its own envelope in production (2 vCPU / 6 GB per `classify-service/README.md`), so 2 is the
#: honest thread count for a per-event cost claim rather than this laptop's full core count.
STARTER_VCPU = 2


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--checkpoint", default="microsoft/deberta-large-mnli")
    ap.add_argument("--corpus", default="generated")
    ap.add_argument("--threads", type=int, default=STARTER_VCPU)
    ap.add_argument("--samples", type=int, default=24, help="pairs timed per tiling")
    args = ap.parse_args()

    import torch

    torch.set_num_threads(args.threads)

    pairs, _, provenance = load_corpus(args.corpus)
    print(f"checkpoint: {args.checkpoint}")
    print(f"corpus:     {args.corpus} — {provenance}")
    print(f"threads:    {args.threads} (starter host is {STARTER_VCPU} vCPU)")

    # STRATIFIED BY SHAPE, not strided. The pairs are laid out claim-major with one entry per
    # premise shape, so any stride that is a multiple of the shape count samples ONE shape and
    # nothing else — and a single-document premise makes byte and document tiling identical, so the
    # cost difference this script exists to measure silently reads as zero. That happened: at 585
    # pairs the stride was coprime with the shape count and the sample was mixed, at 1370 it was
    # not. Aliasing against the corpus layout is not a risk worth leaving in a cost gate.
    by_shape: dict[str, list] = {}
    for pair in pairs:
        by_shape.setdefault(pair.slices["shape"], []).append(pair)
    per_shape = max(1, args.samples // len(by_shape))
    sample = [p for shape in sorted(by_shape) for p in by_shape[shape][:per_shape]]
    inputs = [(p.premise, p.claim) for p in sample]
    docs = [p.documents for p in sample]

    print(
        f"sample:     {len(sample)} pairs, "
        + ", ".join(f"{s}={sum(1 for p in sample if p.slices['shape'] == s)}" for s in sorted(by_shape))
        + "\n"
    )
    print(f"  {'tiling':<16} {'runtime':<11} {'passes/obs':>11} {'sec/obs':>9} {'obs/sec':>9} {'sec/1k obs':>11}")
    print("  " + "-" * 72)
    for name in ("byte", "doc"):
        cfg = next(c for a, c in ARMS.items() if c.tiling == name and c.reduce == "min" and "pair" not in a)
        scorer = LocalNliPairScorer(args.checkpoint, cfg, batch=8)
        runtime = scorer.runtime
        passes = scorer.forward_passes(inputs, docs)

        scorer.score_pairs(inputs[:2], docs[:2])  # warm the graph; first call pays lazy init
        runs = []
        for _ in range(2):
            started = time.perf_counter()
            scorer.score_pairs(inputs, docs)
            runs.append(time.perf_counter() - started)
        elapsed = statistics.median(runs)

        per_obs = elapsed / len(sample)
        print(
            f"  {name:<16} {runtime:<11} {passes / len(sample):>11.1f} {per_obs:>9.3f} "
            f"{1 / per_obs:>9.2f} {per_obs * 1000:>11.0f}"
        )

    print(
        "\n  One scored observation is ONE claim against its evidence. A turn asserting several\n"
        "  checkable sentences costs this much per sentence, so multiply by the claims per turn\n"
        "  before comparing against an ingest budget."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
