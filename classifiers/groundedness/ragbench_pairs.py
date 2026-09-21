# SPDX-License-Identifier: Apache-2.0
"""RAGBench (Galileo, CC BY 4.0) as a second external corpus — with its label caveat stated up front.

WHAT IT IS. ~100k RAG responses over 12 source datasets (biomedical, legal, finance, customer
support, general QA), each response split into sentences and each sentence marked supported or
not by the retrieved documents, with the documents' real boundaries preserved. It is broad where
RAGTruth is deep.

WHAT IT IS NOT. The annotator is GPT-4o, not a person. That makes it the same KIND of evidence as
our own generated corpora — an LLM's opinion of groundedness — from a different model family. It
cannot confirm what RAGTruth's human labels say; it can only agree or disagree with them.

TWO LABEL VIEWS, BECAUSE THE SCOPES DO NOT LINE UP. RAGBench's positive class is "not fully
supported", which merges the two things `labels.py` deliberately separates:

    strict  — positive only when the annotator's explanation says the sentence CONTRADICTS the
              documents (contradict / conflict / incorrect / instead / ...). Fully-supported
              sentences are negatives; every other unsupported sentence is EXCLUDED as out of scope.
              This is the classifier's own contract, with a keyword filter standing in for a
              conflict-vs-baseless annotation RAGBench never made.
    broad   — positive = any unsupported sentence. This is RAGBench's adherence metric as published.
              A contradiction-only classifier is EXPECTED to score low here; the number says how
              much of the "hallucination" traffic a deployment will see is outside its scope.

CONFIGS. Prose-document configs only. `cuad` (single contracts averaging 42k characters), `finqa`
and `tatqa` (tables; GPT-4o marks >95% of sentences unsupported because computed numbers are not
verbatim in the source) are excluded for the same reason RAGTruth's Data2txt is: the premise is not
prose and the catalog entry withdraws structured data as evidence. `pubmedqa` is subsampled so its
14k sentences do not become the whole corpus.
"""

from __future__ import annotations

import json
import re
from collections import Counter
from pathlib import Path

from .pairs import Pair

CONFIGS = ("covidqa", "delucionqa", "emanual", "expertqa", "hagrid", "hotpotqa", "msmarco",
           "pubmedqa", "techqa")
MAX_RESPONSES = {"pubmedqa": 300}
# Strong wording only. A first pass that also matched "incorrect", "does not match" and "wrong"
# pulled in paraphrase complaints ("omits exact details", "the phrase 'passage 2' seems incorrectly
# specified") — 3 of 5 sampled positives were not contradictions. These forms name a conflict with
# the source, and a sample of them read as such.
_CONTRA = re.compile(
    r"contradict|conflicts? with|incorrectly (?:states|claims|says|asserts|identifies|attributes)|"
    r"instead of|rather than|falsely|misstat|misrepresent|the opposite|"
    r"(?:document|source|context)s? (?:actually |clearly )?(?:states?|says?|lists?|indicates?) [^.]{0,80}\b(?:not|but)\b",
    re.I,
)
MIN_SENT_CHARS = 20
RELABELS = Path("data/groundedness/ragbench_relabels.json")


def load(view: str = "strict", split: str = "test") -> tuple[list[Pair], dict]:
    from datasets import load_dataset

    if view not in ("strict", "broad"):
        raise ValueError(f"view must be strict|broad, got {view!r}")
    # Judge-confirmed contradictions (see ragbench_relabel). Without them the strict view is a
    # keyword filter over GPT-4o prose, and a sample of that was mostly not contradictions.
    relabels = json.loads(RELABELS.read_text(encoding="utf-8")) if RELABELS.exists() else None
    if relabels is None:
        print("WARNING: no ragbench_relabels.json — strict view uses the keyword filter (noisy)")
    pairs: list[Pair] = []
    responses = 0
    for cfg in CONFIGS:
        ds = load_dataset("galileo-ai/ragbench", cfg, split=split)
        cap = MAX_RESPONSES.get(cfg, len(ds))
        for n, r in enumerate(ds):
            if n >= cap:
                break
            documents = tuple(d.strip() for d in r["documents"] if d and d.strip())
            if not documents:
                continue
            responses += 1
            support = {}
            for si in r["sentence_support_information"]:
                support[si.get("response_sentence_key")] = si
            for key, sent in r["response_sentences"]:
                sent = (sent or "").strip()
                if len(sent) < MIN_SENT_CHARS:
                    continue
                si = support.get(key, {})
                supported = bool(si.get("fully_supported", key not in r["unsupported_response_sentence_keys"]))
                if relabels is not None:
                    contra = relabels.get(f"ragbench:{cfg}:{r['id']}\t{sent}", False)
                else:
                    contra = bool(_CONTRA.search(si.get("explanation") or ""))
                if supported:
                    kind, label = "supported", 0
                elif view == "broad":
                    kind, label = ("contradiction" if contra else "unsupported"), 1
                elif contra:
                    kind, label = "contradiction", 1
                else:
                    continue  # unsupported-but-not-contradiction: out of scope in the strict view
                pairs.append(Pair(
                    documents=documents, premise="\n\n".join(documents), claim=sent, label=label,
                    source=f"ragbench:{cfg}:{r['id']}",
                    slices={"kind": kind, "shape": cfg, "domain": r["generation_model_name"],
                            "decontextualized": "false", "cluster": f"{cfg}:{r['id']}"},
                ))
    meta = {"corpus": f"ragbench-{view}", "split": split, "responses": responses,
            "licence": "CC BY 4.0", "annotator": "gpt-4o", "kappa": None,
            "claims": [{"kind": p.slices["kind"]} for p in pairs]}
    return pairs, meta


def summary(view: str = "strict") -> str:
    pairs, meta = load(view)
    pos = sum(p.label for p in pairs)
    return "\n".join([
        f"RAGBench {view} test: {meta['responses']} responses -> {len(pairs)} sentences",
        f"  positive: {pos} ({pos / len(pairs):.1%} prevalence)",
        f"  by kind: {dict(Counter(p.slices['kind'] for p in pairs))}",
        f"  by config: {dict(Counter(p.slices['shape'] for p in pairs))}",
        f"  documents per premise: mean {sum(len(p.documents) for p in pairs) / len(pairs):.1f}, "
        f"premise chars: mean {sum(len(p.premise) for p in pairs) / len(pairs):.0f}",
    ])


if __name__ == "__main__":
    import sys

    print(summary(sys.argv[1] if len(sys.argv) > 1 else "strict"))
