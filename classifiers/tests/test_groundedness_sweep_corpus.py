# SPDX-License-Identifier: Apache-2.0
"""Port-parity pin: `groundedness/sweep_corpus.py` <-> `classify-service/classify.js`.

The Python copy exists so an offline run tiles the premise exactly the way the service does. A
copy with nothing pinning it drifts silently — both sides keep working, they just stop being the
same thing, and every number published in the interval describes a population production never
scored. So this does not test the Python against a hand-written expectation; it runs the REAL
`windowsFor`/`premiseChunksFor` out of `classify.js` in node and compares outputs character for
character.

`scripts/check-classifier-parity.sh` names this pin.
"""

from __future__ import annotations

import json
import shutil
import subprocess
from pathlib import Path

import pytest

from groundedness import sweep_corpus

CLASSIFY_JS = Path(__file__).resolve().parents[2] / "classify-service" / "classify.js"

# Inputs chosen to hit each branch of windowsFor: under-window, exactly one window, the tail-window
# append, and well past maxWindows so the even-spacing pick runs (the branch with the Math.round
# hazard). Claim lengths vary because the claim's share of the budget changes the window size.
CASES = [
    ("", 10),
    ("short premise", 10),
    ("x" * 1799, 20),
    ("x" * 1800, 20),
    ("x" * 1801, 20),
    ("y" * 3600, 50),
    ("z" * 7000, 200),
    ("q" * 20000, 400),
    ("w" * 40000, 999),  # past PAIR_MAX_PREMISE_CHARS, so the clamp shows up too
    ("abcdefghij" * 900, 1),
    ("m" * 5000, 1700),  # claim eats nearly the whole budget -> the 50-char floor
]

_DRIVER = """
const { premiseChunksFor, deBlob } = require(process.argv[1]);
const cases = JSON.parse(process.argv[2]);
const PAIR_MAX_PREMISE_CHARS = 20000;
const PAIR_CLAIM_MAX_CHARS = 1000;
const out = cases.map(([premise, claimLen]) => {
  // Mirror classifyPairs: clamp premise and claim, deBlob both, then chunk against claim+eos.
  const p = deBlob(premise.length > PAIR_MAX_PREMISE_CHARS ? premise.slice(0, PAIR_MAX_PREMISE_CHARS) : premise);
  const raw = 'c'.repeat(claimLen);
  // deBlob the claim BEFORE measuring it, exactly as classifyPairs does — an unbroken claim gains
  // a space, which changes the premise budget by one char. This ordering is the whole point.
  const claim = deBlob(raw.length > PAIR_CLAIM_MAX_CHARS ? raw.slice(0, PAIR_CLAIM_MAX_CHARS) : raw);
  return premiseChunksFor(p, claim.length + '</s>'.length);
});
process.stdout.write(JSON.stringify(out));
"""


@pytest.mark.skipif(shutil.which("node") is None, reason="node is not on PATH")
def test_premise_chunks_match_classify_js():
    assert CLASSIFY_JS.exists(), f"{CLASSIFY_JS} is missing — this pin has nothing to compare against"
    proc = subprocess.run(
        ["node", "-e", _DRIVER, str(CLASSIFY_JS), json.dumps(CASES)],
        capture_output=True,
        text=True,
        timeout=120,
    )
    assert proc.returncode == 0, f"node driver failed:\n{proc.stderr}"
    js_chunks = json.loads(proc.stdout)

    for (premise, claim_len), expected in zip(CASES, js_chunks):
        got, _ = sweep_corpus.prepare_pair(premise, "c" * claim_len)
        assert got == expected, (
            f"tiling diverged for premise len={len(premise)} claim len={claim_len}: "
            f"python produced {len(got)} chunks {[len(c) for c in got]}, "
            f"classify.js produced {len(expected)} chunks {[len(c) for c in expected]}"
        )


def test_js_round_is_half_away_from_zero_not_bankers():
    # The specific hazard the even-spacing pick depends on. Python's round(2.5) is 2.
    assert sweep_corpus._js_round(2.5) == 3
    assert sweep_corpus._js_round(3.5) == 4
    assert sweep_corpus._js_round(2.4) == 2


def test_groundedness_reduces_by_min_every_other_head_by_max():
    # The reduction has to agree with what the head's score MEANS. groundedness scores
    # `1 - P(contradiction)`, so "contradicted if ANY window contradicts" is MIN over support.
    support = [0.95, 0.05, 0.94]
    assert sweep_corpus.reducer_for("groundedness")(support) == 0.05
    assert sweep_corpus.reducer_for("frustration")(support) == 0.95
    assert sweep_corpus.reducer_for("some-future-head")(support) == 0.95
