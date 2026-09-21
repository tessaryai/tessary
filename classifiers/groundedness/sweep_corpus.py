# SPDX-License-Identifier: Apache-2.0
"""The pair head's premise tiling and window reduction, in Python.

This is a SHIPPING COPY of `classify-service/classify.js` — `deBlob`, `windowsFor`,
`premiseChunksFor` and `REDUCERS`. It exists because an offline run that tiles differently from the
service scores a different POPULATION than production does, and the numbers then describe a
detector nobody runs. `scripts/check-classifier-parity.sh` names this pin; `tests/
test_groundedness_sweep_corpus.py` is what enforces it, by running the real JS against the same
inputs rather than trusting this file to have been ported correctly.

Port hazards that are easy to get wrong and are pinned by that test:

- JS `Math.round` rounds half AWAY from zero; Python's `round` is banker's rounding. The
  even-spacing pick in `windowsFor` uses it, so `round(2.5)` differing by one gives a different
  window set. `_js_round` below is the JS rule.
- `String.prototype.slice` clamps out-of-range indices rather than raising, which Python slicing
  also does — that one happens to agree, so it is not defended, only noted.
- The premise is clamped BEFORE tiling and the claim is clamped separately, because the claim is
  the fixed part of every window's input.
"""

from __future__ import annotations

import math
import re

# Defaults from classify.js. Named the same so a diff between the two files is readable.
WINDOW_CHARS = 1800
WINDOW_OVERLAP = 200
MAX_WINDOWS = 4

PAIR_TOTAL_CHARS = 1800
PAIR_CLAIM_MAX_CHARS = 1000
PAIR_OVERLAP = 200
PAIR_MAX_CHUNKS = 4
PAIR_MAX_PREMISE_CHARS = 20000

_BLOB = re.compile(r"(\S{256})(?=\S)")


def de_blob(text: str) -> str:
    """`deBlob`: break unbroken runs so tokenization cannot blow up on a base64 blob."""
    return _BLOB.sub(r"\1 ", text)


def _js_round(x: float) -> int:
    """JS `Math.round`: half rounds toward +Infinity, not to even."""
    return math.floor(x + 0.5)


def windows_for(
    text: str,
    window_chars: int = WINDOW_CHARS,
    overlap: int = WINDOW_OVERLAP,
    max_windows: int = MAX_WINDOWS,
) -> list[str]:
    """`windowsFor`: overlapping char windows, capped at `max_windows` by EVEN SPACING.

    The cap is the part worth reading twice. Past `max_windows` the windows are not the first N —
    they are sampled evenly across the whole text, so most of a long premise is never scored and
    which part survives is decided by byte offset rather than by relevance to the claim.
    """
    overlap = min(overlap, window_chars - 1)
    max_windows = max(1, max_windows)
    if len(text) <= window_chars or max_windows == 1:
        return [text[:window_chars]]

    stride = max(1, window_chars - overlap)
    starts: list[int] = []
    s = 0
    while s < len(text):
        starts.append(s)
        if s + window_chars >= len(text):
            break
        s += stride

    last_start = max(0, len(text) - window_chars)
    if starts[-1] != last_start:
        starts.append(last_start)

    chosen = starts
    if len(starts) > max_windows:
        picked = [starts[_js_round(i * (len(starts) - 1) / (max_windows - 1))] for i in range(max_windows)]
        chosen = list(dict.fromkeys(picked))  # dedup, insertion-ordered, like `new Set`
    return [text[s : s + window_chars] for s in chosen]


def premise_chunks_for(
    premise: str,
    claim_len: int,
    total_chars: int = PAIR_TOTAL_CHARS,
    overlap: int = PAIR_OVERLAP,
    max_chunks: int = PAIR_MAX_CHUNKS,
) -> list[str]:
    """`premiseChunksFor`: window the premise, reserving the claim's share of the budget."""
    chunk_chars = max(50, total_chars - claim_len)
    return windows_for(premise, window_chars=chunk_chars, overlap=overlap, max_windows=max_chunks)


# `REDUCERS` / `DEFAULT_REDUCER` in classify.js. `groundedness` scores `1 - P(contradiction)`, so
# "contradicted if ANY window contradicts" is MIN over support. Every other head is MAX.
REDUCERS = {
    "groundedness": min,
    "_default": max,
}


def reducer_for(head: str):
    return REDUCERS.get(head, REDUCERS["_default"])


def prepare_pair(premise: str, claim: str, eos: str = "</s>", **chunk_opts) -> tuple[list[str], str]:
    """The exact inputs `classifyPairs` builds: `(premise_chunks, clamped_claim)`.

    Callers join as `chunk + eos + claim`, which is what the service sends to the tokenizer.
    """
    premise = de_blob(premise[:PAIR_MAX_PREMISE_CHARS])
    claim = de_blob(claim[:PAIR_CLAIM_MAX_CHARS])
    return premise_chunks_for(premise, len(claim) + len(eos), **chunk_opts), claim
