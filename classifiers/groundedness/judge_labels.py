# SPDX-License-Identifier: Apache-2.0
"""Label claims with an independent judge, and measure how far it agrees with the authored labels.

WHY THIS EXISTS. `pairs.py` was written by one person, who wrote both the claims and the documents
they contradict. Every number `eval_ablations.py` reports rests on those labels being right, and
nothing so far has tested that. A judge is not a second human annotator, but it is a genuinely
independent process, and Cohen's kappa against it is a far better answer than "the author checked
their own work".

HOW TO READ THE RESULT. Kappa bands are `framework.agreement`'s: >= 0.8 reliable, >= 0.667
tentative, below that unreliable. A LOW kappa does not automatically mean the authored labels are
wrong — the judge can be wrong too, and this label is deliberately narrow in a way a general reader
gets wrong (a claim the source is SILENT on is negative here). That is why disagreements are
printed in full rather than counted: they are an adjudication queue, not a verdict.

Run:
    TESSARY_JUDGE=cli uv run python -m groundedness.judge_labels
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from framework.agreement import cohen_kappa
from framework.judge import Verdict, classify, default_judge

from .labels import SPEC
from .pairs import CLAIMS, DOCS_BY_ID

OUT = Path("data/groundedness/judge_labels.json")

#: The judge sees exactly what the label claims: the claim against its SOURCE DOCUMENT ALONE, no
#: distractors. Labelling against a multi-document premise would conflate "is this claim
#: contradicted" with "can the judge find the right passage", and only the first is the label.
ITEM = "SOURCE:\n{source}\n\nCLAIM:\n{claim}"


def judge_pairs(judge, pairs: list[tuple[str, str]], batch_size: int = 12) -> list[Verdict]:
    """Label (source_text, claim_text) pairs. Shared with `verify_corpus`."""
    texts = [ITEM.format(source=source, claim=claim) for source, claim in pairs]
    return classify(judge, texts, SPEC, batch_size=batch_size)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--out", default=str(OUT))
    ap.add_argument("--batch-size", type=int, default=12)
    args = ap.parse_args()

    judge = default_judge(allow_fake=False)
    print(f"judge: {type(judge).__name__}  |  claims: {len(CLAIMS)}\n")

    pairs = [(DOCS_BY_ID[c.source].text, c.text) for c in CLAIMS]
    verdicts = judge_pairs(judge, pairs, args.batch_size)

    from .labels import is_positive

    rows = []
    disagreements = []
    for i, (claim, verdict) in enumerate(zip(CLAIMS, verdicts)):
        item_id = f"claim{i:03d}"
        authored = is_positive(claim.kind)
        rows.append((item_id, "authored", authored))
        rows.append((item_id, "judge", verdict.label))
        if authored != verdict.label:
            disagreements.append((item_id, claim, authored, verdict))

    report = cohen_kappa(rows, "authored", "judge")
    print(report.render())
    print()

    # Per-kind, because a single kappa hides which class the two processes read differently — and
    # the out-of-scope kinds are exactly where a general reader is expected to diverge.
    by_kind: dict[str, list[int]] = {}
    for claim, verdict in zip(CLAIMS, verdicts):
        by_kind.setdefault(claim.kind, []).append(int(is_positive(claim.kind) == verdict.label))
    print("agreement by kind:")
    for kind, hits in sorted(by_kind.items()):
        print(f"  {kind:<18} {sum(hits)}/{len(hits)}")

    if disagreements:
        print(f"\nadjudication queue — {len(disagreements)} disagreement(s):")
        for item_id, claim, authored, verdict in disagreements:
            print(f"\n  [{item_id}] kind={claim.kind} authored={authored} judge={verdict.label} "
                  f"conf={verdict.confidence:.2f}")
            print(f"    claim:  {claim.text}")
            print(f"    source: {claim.source}")
            print(f"    judge:  {verdict.reason[:180]}")
    else:
        print("\nno disagreements.")

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(
        json.dumps(
            {
                "judge": type(judge).__name__,
                "kappa": report.value,
                "band": report.band,
                "raw_agreement": report.raw_agreement,
                "labels": [
                    {
                        "claim": c.text,
                        "kind": c.kind,
                        "source": c.source,
                        "authored": is_positive(c.kind),
                        "judge": v.label,
                        "confidence": v.confidence,
                        "reason": v.reason,
                    }
                    for c, v in zip(CLAIMS, verdicts)
                ],
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
