# SPDX-License-Identifier: Apache-2.0
"""RAGTruth as TOKEN-labelled examples for a long-context span classifier.

WHY A DIFFERENT SHAPE. The pair classifier reads (1,800-char window, one sentence) sixteen times per
claim and plateaued at 0.15 recall on RAGTruth however it was trained (README, "External validity").
The encoders that do well on this corpus read the whole context and the whole response in ONE pass
and label each response token — the model sees every passage at once, sees the response's own
sentences in context, and is trained on real hallucinations from step one rather than as a late
mix-in. This module builds that input.

INPUT LAYOUT. `[CLS] query? + context [SEP] response [SEP]`, with the context truncated first if the
pair overflows (`truncation="only_first"`); RAGTruth's longest Summary/QA context is ~10.7k chars
(~2.7k tokens), so at 4,096 tokens nothing is cut. Labels sit on RESPONSE tokens only:

    0  O          the token is inside no annotated span
    1  BASELESS   inside an Evident/Subtle Baseless Info span   (out of the pair head's scope,
                                                                 kept as its own class, never merged)
    2  CONFLICT   inside an Evident/Subtle Conflict span         (the positive class)
   -100            context, special tokens and padding: no loss

Keeping BASELESS as a class rather than folding it into O is deliberate: the annotators marked it,
it is the confusable neighbour of CONFLICT, and a deployment may later want it as a separate signal.
The served groundedness score is P(BASELESS) + P(CONFLICT) at the strongest sentence (`labels.UNSUPPORTED_SPEC`);
P(CONFLICT) alone is returned alongside for the narrower, pair-head-era question.
"""

from __future__ import annotations

import json

from .ragtruth_pairs import BASELESS, CONFLICT, TASKS

LABELS = ("O", "BASELESS", "CONFLICT")
O, BASELESS_ID, CONFLICT_ID = 0, 1, 2


def rows(split: str = "train", include_data2txt: bool = False):
    """Raw RAGTruth rows this task uses: Summary and QA, quality 'good'.

    `include_data2txt` adds the Data2txt task for TRAINING only: its context is a JSON object,
    which the catalog withdraws as evidence and which is never evaluated here — but its conflict
    spans (a number or field rendered wrongly) are still human-labelled contradictions, and there
    are ~3x as many of them as in Summary+QA combined. Whether that transfers is an experiment."""
    from datasets import load_dataset

    tasks = TASKS + (("Data2txt",) if include_data2txt else ())
    for r in load_dataset("wandb/RAGTruth-processed")[split]:
        if r["task_type"] in tasks and r["quality"] == "good":
            yield r


#: How the context side is laid out. "plain" = question + raw context (runs #1-#4). "lettuce" =
#: LettuceDetect's own training prompt — numbered passages under its QA instruction, or its summary
#: instruction — so a checkpoint that started life as lettucedetect sees the layout it was trained on.
#: Serving must use the same template as training; the choice is recorded in training_provenance.
TEMPLATES = ("plain", "lettuce")
_LETTUCE_QA = ("Briefly answer the following question:\n{question}\nBear in mind that your response should be "
               "strictly based on the following {n} passages:\n{context}\nIn case the passages do not contain the "
               "necessary information to answer the question, please reply with: \"Unable to answer based on "
               "given passages.\"\noutput:")
_LETTUCE_SUMMARY = "Summarize the following text:\n{text}\noutput:"


def lettuce_prompt(passages: list[str], question: str | None) -> str:
    ctx = "\n".join(f"passage {i + 1}: {p}" for i, p in enumerate(passages))
    if question:
        return _LETTUCE_QA.format(question=question, n=len(passages), context=ctx)
    return _LETTUCE_SUMMARY.format(text=ctx)


def context_of(r: dict, template: str = "plain") -> str:
    q = (r.get("query") or "").strip()
    if template == "lettuce":
        passages = [p.strip() for p in r["context"].split("\n\n") if p.strip()] or [r["context"]]
        return lettuce_prompt(passages, q if r["task_type"] == "QA" and q else None)
    return f"{q}\n\n{r['context']}" if r["task_type"] == "QA" and q else r["context"]


def spans_of(r: dict) -> list[tuple[int, int, int]]:
    """(start, end, label_id) character spans over the response."""
    out = []
    for s in json.loads(r["hallucination_labels"]):
        kind = s.get("label_type")
        if kind in CONFLICT:
            out.append((s["start"], s["end"], CONFLICT_ID))
        elif kind in BASELESS:
            out.append((s["start"], s["end"], BASELESS_ID))
    return out


def encode(tok, context: str, response: str, spans, max_length: int) -> dict:
    """One tokenised example with token labels aligned to `spans` via offset mapping."""
    enc = tok(context, response, truncation="only_first", max_length=max_length,
              return_offsets_mapping=True)
    seq = enc.sequence_ids()
    labels = []
    for i, (a, b) in enumerate(enc["offset_mapping"]):
        if seq[i] != 1 or b <= a:
            labels.append(-100)
            continue
        lab = O
        for s, e, l in spans:
            if a < e and b > s:
                lab = max(lab, l)  # CONFLICT wins where spans overlap
        labels.append(lab)
    enc["labels"] = labels
    enc.pop("offset_mapping")
    return enc


def build(tok, split: str, max_length: int, limit: int = 0, include_data2txt: bool = False,
          template: str = "plain") -> list[dict]:
    out = []
    for n, r in enumerate(rows(split, include_data2txt)):
        if limit and n >= limit:
            break
        out.append(encode(tok, context_of(r, template), r["output"], spans_of(r), max_length))
    return out


# --- Our own generated corpora, as token examples -----------------------------------------------
# The RAGTruth-only diet taught the model news summaries and web QA and nothing about the
# product/support/policy claims the generated corpora are made of (0.05 recall on them against the
# pair head's 0.88). These are the TUNING corpora only (`verified_corpus*.json`); the held-out files
# are never loaded here. Each pair becomes one example: the retrieved documents as context, the
# claim as the whole "response", with every response token labelled by the pair's kind:
#     contradiction / compound  -> CONFLICT   (compound labels the whole sentence; one clause is the
#                                              conflict, which is noisy but is also what the sentence-
#                                              level metric asks for)
#     neutral_addition          -> BASELESS
#     tool_fact                 -> BASELESS  (true, but absent from the retrieved evidence: that IS
#                                             "unsupported" under labels.UNSUPPORTED_SPEC. Run #3
#                                             labelled it O and the model duly learned tool facts
#                                             are fine — the opposite of what the evaluation asks.)
#     support                   -> O
# Premises run to ~24k characters (six documents); at max_length 4096 the context is truncated from
# the end, so "six_deep" shapes may lose the contradicting document. That is a known loss, not a bug.
_KIND_LABEL = {"contradiction": CONFLICT_ID, "compound": CONFLICT_ID,
               "neutral_addition": BASELESS_ID, "tool_fact": BASELESS_ID}


def generated_context(p, template: str = "plain") -> str:
    """The generated corpora have no question, so the lettuce layout uses the summary instruction."""
    return lettuce_prompt(list(p.documents), None) if template == "lettuce" else p.premise


def generated_examples(tok, max_length: int, template: str = "plain") -> list[dict]:
    from .generated_pairs import load

    pairs, _ = load()
    out = []
    for p in pairs:
        lab = _KIND_LABEL.get(p.slices["kind"], O)
        spans = [(0, len(p.claim), lab)] if lab != O else []
        out.append(encode(tok, generated_context(p, template), p.claim, spans, max_length))
    return out
