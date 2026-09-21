# SPDX-License-Identifier: Apache-2.0
"""Relabel RAGBench's candidate contradictions against OUR label spec.

RAGBench never distinguishes "contradicts the source" from "the source is silent"; both are
"not fully supported". A keyword filter over GPT-4o's free-text explanation was tried first and a
sample of its hits were mostly paraphrase complaints, so it is a candidate generator here, nothing
more. Every candidate — any unsupported sentence whose explanation uses conflict wording under the
LOOSE pattern — is judged against `labels.SPEC` by the same independent judge `verify_corpus` uses,
with the response's real documents as the source. Only confirmed contradictions become positives.

Writes data/groundedness/ragbench_relabels.json: {"<source>\\t<claim>": true|false}. `ragbench_pairs`
reads it when present; without it the strict view falls back to the (noisy) keyword filter and
says so.
"""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

from framework.judge import ClaudeCliJudge

from .judge_labels import judge_pairs

OUT = Path("data/groundedness/ragbench_relabels.json")
_LOOSE = re.compile(
    r"contradict|conflict|incorrect|inaccurate|wrong|mistaken|misstat|does not match|not match|"
    r"instead|rather than|falsely|in error|misrepresent|opposite", re.I,
)


def candidates() -> list[tuple[str, str, str]]:
    """(source_id, documents_text, claim) for every loosely-matching unsupported sentence."""
    from datasets import load_dataset

    from .ragbench_pairs import CONFIGS, MAX_RESPONSES, MIN_SENT_CHARS

    out = []
    for cfg in CONFIGS:
        ds = load_dataset("galileo-ai/ragbench", cfg, split="test")
        for n, r in enumerate(ds):
            if n >= MAX_RESPONSES.get(cfg, len(ds)):
                break
            docs = "\n\n".join(d.strip() for d in r["documents"] if d and d.strip())
            support = {si.get("response_sentence_key"): si for si in r["sentence_support_information"]}
            for key, sent in r["response_sentences"]:
                sent = (sent or "").strip()
                si = support.get(key, {})
                if len(sent) < MIN_SENT_CHARS or si.get("fully_supported", True):
                    continue
                if _LOOSE.search(si.get("explanation") or ""):
                    out.append((f"ragbench:{cfg}:{r['id']}", docs, sent))
    return out


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--model", default="claude-sonnet-5")
    ap.add_argument("--batch-size", type=int, default=8)
    args = ap.parse_args()

    cands = candidates()
    print(f"candidates: {len(cands)}")
    judge = ClaudeCliJudge(model=args.model)
    verdicts = judge_pairs(judge, [(docs[:20000], claim) for _, docs, claim in cands], args.batch_size)
    labels = {f"{src}\t{claim}": bool(v.label) for (src, _, claim), v in zip(cands, verdicts)}
    OUT.write_text(json.dumps(labels, indent=1, ensure_ascii=False), encoding="utf-8")
    n = sum(labels.values())
    print(f"confirmed contradictions: {n} of {len(cands)}  -> {OUT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
