# SPDX-License-Identifier: Apache-2.0
"""The native groundedness server's contract: request validation mirrors groundedness.js's, and the
per-sentence reduction produces the same shape and numbers groundedness.js's `reduce` would from the
same per-token probabilities. The encoding itself is pinned by test_groundedness_token_head.py."""

from __future__ import annotations

import pytest

from groundedness import serve


def test_validate_mirrors_the_js_contract():
    ok = serve.validate({"head": "groundedness", "responses": [{"passages": ["p"], "answer": "a"}]})
    assert ok == [{"passages": ["p"], "answer": "a"}]
    with pytest.raises(ValueError, match="UNAVAILABLE_IN_OPEN_EDITION"):
        serve.validate({"head": "frustration", "texts": ["x"]})
    with pytest.raises(ValueError, match="not texts or pairs"):
        serve.validate({"head": "groundedness", "texts": ["x"]})
    with pytest.raises(ValueError, match="non-empty array"):
        serve.validate({"head": "groundedness", "responses": [{"passages": [], "answer": "a"}]})
    with pytest.raises(ValueError, match="exceeds"):
        serve.validate({"head": "groundedness", "responses": [{"passages": ["p"], "answer": "a"}] * 17})


def test_gate_bounds_inflight_and_queue_and_answers_the_rest_with_a_refusal():
    gate = serve.Gate(max_inflight=1, max_queue=1, timeout_s=0.05)
    assert gate.acquire() is True, "the one slot, taken without queueing"
    assert gate.acquire() is False, "the one queue place times out waiting for the slot"
    gate.release()
    assert gate.acquire() is True, "the released slot is reusable"
    gate.release()
    strict = serve.Gate(max_inflight=1, max_queue=0, timeout_s=0.05)
    assert strict.acquire() is True, "no queue still means the free slot is granted"
    assert strict.acquire() is False, "and the second concurrent request is refused at once"


def test_reduce_takes_the_max_token_per_sentence_and_skips_short_ones():
    answer = "The refund takes two hours to arrive. Ok. Second sentence is also long enough."
    # Three "tokens" per sentence, laid over character offsets; one context token first (seq 0).
    offsets = [[0, 0], [0, 10], [10, 25], [25, 37], [38, 41], [42, 60], [60, 80]]
    seq = [0, 1, 1, 1, 1, 1, 1]
    o = [1.0, 0.0, 0.0]
    probs = [o, [0.9, 0.05, 0.05], [0.2, 0.7, 0.1], o, [0.0, 0.0, 1.0], [0.5, 0.1, 0.4], o]
    out = serve.reduce(probs, offsets, seq, answer)
    assert [s["start"] for s in out["spans"]] == [0, 42], "the 'Ok.' sentence is under MIN_SENT_CHARS"
    first, second = out["spans"]
    assert first["unsupported"] == pytest.approx(0.8) and first["conflict"] == pytest.approx(0.1)
    assert second["unsupported"] == pytest.approx(0.5) and second["conflict"] == pytest.approx(0.4)
    assert out["unsupported"] == pytest.approx(0.8) and out["conflict"] == pytest.approx(0.4)
