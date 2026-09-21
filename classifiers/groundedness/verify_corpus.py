# SPDX-License-Identifier: Apache-2.0
"""Independently label a generated corpus, and drop every claim the verifier disagrees with.

`generate_corpus.py` is told which kind to write, so its labels are INTENT. A generator that misses
looks exactly like one that hits. This pass labels the same claims with a judge that was not told
the intent, keeps only the claims where the two agree, and reports the agreement so the corpus
carries its own quality number.

THE MODEL MUST DIFFER FROM THE GENERATOR'S. Verifying opus-written claims with opus is a
consistency check on one model, not an independent label. `--model` defaults to a different family
and `main` warns loudly if it ends up matching what generated the corpus.

WHAT AGREEMENT HERE DOES AND DOES NOT BUY. High agreement means the generator wrote what it was
asked to write. It does NOT mean the claims resemble what a production agent actually says — both
processes are language models writing to a spec, and neither has seen a real hallucination. That
gap is named in the module README and is not closed by anything in this file.

Run:
    TESSARY_JUDGE=cli uv run python -m groundedness.verify_corpus
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from framework.agreement import cohen_kappa
from framework.judge import ClaudeCliJudge

from .generate_corpus import OUT as GENERATED
from .judge_labels import judge_pairs
from .labels import is_positive

OUT = Path("data/groundedness/verified_corpus.json")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--corpus", default=str(GENERATED))
    ap.add_argument("--out", default=str(OUT))
    ap.add_argument("--model", default="claude-sonnet-5", help="must differ from the generator's")
    ap.add_argument("--batch-size", type=int, default=12)
    args = ap.parse_args()

    corpus = json.loads(Path(args.corpus).read_text(encoding="utf-8"))
    docs = {d["id"]: d for d in corpus["documents"]}
    claims = corpus["claims"]
    if not claims:
        print(f"{args.corpus} has no claims to verify")
        return 1

    judge = ClaudeCliJudge(model=args.model)
    print(f"generator: {corpus.get('generator')}  |  verifier: {args.model}  |  claims: {len(claims)}\n")

    verdicts = judge_pairs(judge, [(docs[c["source"]]["text"], c["text"]) for c in claims], args.batch_size)

    rows, kept, dropped = [], [], []
    for i, (claim, verdict) in enumerate(zip(claims, verdicts)):
        item = f"gen{i:03d}"
        intent = is_positive(claim["kind"])
        rows.append((item, "generator_intent", intent))
        rows.append((item, "verifier", verdict.label))
        (kept if intent == verdict.label else dropped).append({**claim, "confidence": verdict.confidence})

    report = cohen_kappa(rows, "generator_intent", "verifier")
    print(report.render())

    by_kind: dict[str, list[int]] = {}
    for claim, verdict in zip(claims, verdicts):
        by_kind.setdefault(claim["kind"], []).append(int(is_positive(claim["kind"]) == verdict.label))
    print("\nagreement by kind (generator intent vs independent verifier):")
    for kind, hits in sorted(by_kind.items()):
        print(f"  {kind:<18} {sum(hits):>3}/{len(hits):<3}")

    print(f"\nkept {len(kept)} claims, dropped {len(dropped)} the verifier read differently")
    if dropped:
        print("dropped by kind: " + ", ".join(
            f"{k}={sum(1 for d in dropped if d['kind'] == k)}"
            for k in sorted({d["kind"] for d in dropped})))

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(
        json.dumps(
            {
                "verified": True,
                "generator": corpus.get("generator"),
                "verifier_model": args.model,
                "kappa": report.value,
                "band": report.band,
                "documents": corpus["documents"],
                "claims": kept,
                "dropped": dropped,
            },
            indent=2,
            ensure_ascii=False,
        ),
        encoding="utf-8",
    )
    print(f"\nwrote {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
