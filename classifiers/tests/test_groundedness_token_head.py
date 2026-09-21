# SPDX-License-Identifier: Apache-2.0
"""Parity pin: classify-service/groundedness.js builds the SAME encoder input as the Python side.

The token model was evaluated through `token_eval.TokenScorer` — HF tokenizer, pair encoding with
`truncation="only_first"`, sentence splitter `ragtruth_pairs._sentences`. The service re-implements
the encoding by hand (transformers.js has no only-first truncation and returns no offsets). If the
two ever disagree by one id, the served model is no longer the evaluated one and every number in
groundedness/README.md stops applying. So this test runs the real JS under node against the real
checkpoint tokenizer and compares ids and sentence token ranges on inputs that exercise every branch:
QA and summary templates, multi-passage, an answer with quotes/digits/brackets at sentence starts, a
sentence under MIN_SENT_CHARS, and a context long enough to be truncated.

Skips (does not fail) when node, the service's node_modules, or the checkpoint are absent — it is
a pin on the served build, not a unit test of the Python package.
"""

from __future__ import annotations

import json
import shutil
import subprocess
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
SERVICE = ROOT / "classify-service"
CLI = SERVICE / "groundedness_encode_cli.js"
MAX_LENGTH = 8192
# The served checkpoint: the pinned public revision classify-service bakes, read from its own
# manifest so the two cannot drift. Resolved from the Hugging Face cache (tokenizer files only, a
# few MB) so the pin runs wherever the cache is warm and never targets a local training artifact
# that only one machine has.
_MANIFEST = json.loads((SERVICE / "models.json").read_text())["groundedness"]
MODEL = _MANIFEST["model"]
REVISION = _MANIFEST["revision"]


def _checkpoint():
    try:
        from huggingface_hub import snapshot_download

        return Path(snapshot_download(MODEL, revision=REVISION,
                                      allow_patterns=["tokenizer*", "special_tokens_map.json", "config.json"]))
    except Exception:  # noqa: BLE001 — no cache and no network: the pin skips with that reason
        return None


CHECKPOINT = _checkpoint()

CASES = [
    {"passages": ["The judge set a $10,000 bond for her and banned her from social media.",
                  "Dickens was arrested after a threatening post; a firearm and three computers were found."],
     "question": "What happened to Dickens?",
     "answer": "She was denied bond and remains in jail. \"Police\" found a firearm. Ok. 3 computers were seized (allegedly)."},
    {"passages": ["Seventy years ago, Anne Frank died of typhus in a Nazi concentration camp at the age of 15."],
     "question": None,
     "answer": "The sisters contracted typhus and are believed to have died before February 7, 2022.\nA second paragraph follows here."},
    {"passages": ["word " * 9000], "question": "q?", "answer": "A long context forces only-first truncation. Second sentence here."},
]


def _python_side(tok, case):
    from groundedness.ragtruth_pairs import _sentences
    from groundedness.token_data import lettuce_prompt

    ctx = lettuce_prompt(case["passages"], case["question"])
    enc = tok(ctx, case["answer"], truncation="only_first", max_length=MAX_LENGTH, return_offsets_mapping=True)
    seq = enc.sequence_ids()
    answer_start = next(i for i, s in enumerate(seq) if s == 1)
    ranges = []
    for start, end, sent in _sentences(case["answer"]):
        toks = [i for i, ((a, b), s) in enumerate(zip(enc["offset_mapping"], seq)) if s == 1 and a < end and b > start]
        ranges.append([start, end, (toks[0] - answer_start) if toks else None, (toks[-1] - answer_start + 1) if toks else None])
    return enc["input_ids"], answer_start, ranges


@pytest.mark.skipif(
    not shutil.which("node") or not (SERVICE / "node_modules").exists() or not CLI.exists() or CHECKPOINT is None,
    reason="needs node, classify-service/node_modules, groundedness_encode_cli.js and the pinned tokenizer",
)
def test_js_encoding_matches_python():
    from transformers import AutoTokenizer

    tok = AutoTokenizer.from_pretrained(str(CHECKPOINT))
    js = json.loads(subprocess.run(
        ["node", str(CLI)], input=json.dumps({"model_dir": str(CHECKPOINT), "responses": CASES}),
        capture_output=True, text=True, check=True, cwd=SERVICE,
    ).stdout)
    for case, out in zip(CASES, js):
        ids, answer_start, ranges = _python_side(tok, case)
        assert out["ids"] == ids, f"token ids differ for answer {case['answer'][:40]!r}"
        assert out["answerStart"] == answer_start
        # Sentence token ranges: JS derives them from sentence-by-sentence tokenisation, Python from
        # offset mappings; both must land on the same [from, to) for every sentence.
        assert [r[:4] for r in out["sentenceRanges"]] == ranges, "sentence token ranges differ"
    assert len(js[2]["ids"]) == MAX_LENGTH, "the long context must be truncated to exactly MAX_LENGTH"
