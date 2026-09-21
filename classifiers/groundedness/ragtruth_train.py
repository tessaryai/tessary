# SPDX-License-Identifier: Apache-2.0
"""RAGTruth's TRAIN split as fine-tuning rows, in VitaminC's column layout so the two concatenate.

WHY. The first human-labelled evaluation (`ragtruth_pairs`, test split) put the serving-shape model
at 0.04 recall — and the oracle run, which hands the model the annotator's own cited source sentence,
still only reached 0.18. The gap is not windowing; it is what the model thinks a contradiction looks
like. VitaminC contradictions are Wikipedia revisions (a number, a date, a name swapped); RAGTruth's
are what production LLMs actually do to a news article — "denied bond" for "bond set at $10,000",
"the victim was a former student" when it was the suspect. Only in-domain, human-labelled rows teach
that, and RAGTruth's train split has 1,162 of them under an MIT licence. Nothing here touches the
test split.

THE ROWS ARE THE SERVING SHAPE. Each response sentence is paired with the 1,800-character document
windows the service would build (`premise_chunks_for` over each retrieved document), never with a
sentence the annotator excerpted — the model must learn to find the conflict inside the same window it
will be handed in production.

LABELS, per window, in VitaminC's vocabulary:
    conflict sentence, the window that carries the cited source  -> REFUTES
    conflict sentence, any other window                          -> dropped (unknown)
    clean sentence, its most lexically similar window            -> SUPPORTS
    clean sentence, any other window                             -> NOT ENOUGH INFO
    baseless sentence, every window                              -> NOT ENOUGH INFO
"SUPPORTS" for a clean sentence's best window is the one soft label here: clean means "no annotator
found a problem", which is entailment in practice for a summary or an answer but was not asserted as
such. The head only ever reads P(REFUTES), so an entailment/neutral confusion costs nothing it fires
on; a REFUTES label on a window that does not carry the conflict would, which is why those are
dropped rather than guessed.
"""

from __future__ import annotations

import json
import re
from collections import Counter

from .ragtruth_pairs import BASELESS, CONFLICT, MIN_SENT_CHARS, TASKS, _sentences
from .sweep_corpus import PAIR_TOTAL_CHARS, de_blob, windows_for

_ORIGINAL = re.compile(r'Original:\s*"?(.+?)"?\s*(?:\n|$)', re.S)
_TOKEN = re.compile(r"[a-z0-9]{3,}")


def _bag(text: str) -> set[str]:
    return set(_TOKEN.findall(text.lower()))


def _best_window(windows: list[str], anchor: str) -> int:
    a = _bag(anchor)
    return max(range(len(windows)), key=lambda i: len(a & _bag(windows[i])) / (len(a) or 1))


def build(split: str = "train", neutral_per_clean: int = 1, seed: int = 17) -> list[dict]:
    """Rows of {"evidence", "claim", "label"}; `label` is a VitaminC label string."""
    import random

    from datasets import load_dataset

    rng = random.Random(seed)
    rows: list[dict] = []
    for r in load_dataset("wandb/RAGTruth-processed")[split]:
        if r["task_type"] not in TASKS or r["quality"] != "good":
            continue
        spans = json.loads(r["hallucination_labels"])
        documents = [d.strip() for d in r["context"].split("\n\n") if d.strip()]
        windows: list[str] = []
        for doc in documents:
            # Same budget the service applies, with a typical claim's share reserved.
            windows.extend(windows_for(de_blob(doc), window_chars=PAIR_TOTAL_CHARS - 200, overlap=200,
                                       max_windows=10**6))
        if not windows:
            continue
        for start, end, sent in _sentences(r["output"]):
            sent = sent.strip()
            if len(sent) < MIN_SENT_CHARS:
                continue
            hit = [s for s in spans if s["start"] < end and s["end"] > start]
            conflict = [s for s in hit if s.get("label_type") in CONFLICT]
            if conflict:
                cited = next((m.group(1) for s in conflict
                              for m in [_ORIGINAL.search(s.get("meta") or "")] if m), None)
                best = _best_window(windows, cited or sent)
                rows.append({"evidence": windows[best], "claim": sent, "label": "REFUTES"})
            elif any(s.get("label_type") in BASELESS for s in hit):
                for w in rng.sample(windows, min(neutral_per_clean, len(windows))):
                    rows.append({"evidence": w, "claim": sent, "label": "NOT ENOUGH INFO"})
            else:
                best = _best_window(windows, sent)
                rows.append({"evidence": windows[best], "claim": sent, "label": "SUPPORTS"})
                others = [w for i, w in enumerate(windows) if i != best]
                for w in rng.sample(others, min(neutral_per_clean, len(others))):
                    rows.append({"evidence": w, "claim": sent, "label": "NOT ENOUGH INFO"})
    return rows


if __name__ == "__main__":
    rows = build()
    print(f"{len(rows)} rows: {dict(Counter(r['label'] for r in rows))}")
    print(f"evidence chars mean {sum(len(r['evidence']) for r in rows) / len(rows):.0f}, "
          f"max {max(len(r['evidence']) for r in rows)}")
    for r in [x for x in rows if x["label"] == "REFUTES"][:3]:
        print(f"\nREFUTES  claim: {r['claim'][:140]!r}\n         window: {r['evidence'][:200]!r}")
