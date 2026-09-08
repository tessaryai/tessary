# SPDX-License-Identifier: Apache-2.0
"""framework.audit — behavioral tests on deterministic synthetic data."""

import random

import pytest

from framework import EvalItem, LabeledExample, duplicates, label_noise, prevalence, shortcut_control

POSITIVE_TEMPLATES = [
    "ugh this is terrible, nothing works and I am fed up ({i})",
    "this is so annoying, you keep getting it wrong ({i})",
    "I am really frustrated, that is not what I asked for at all ({i})",
    "seriously? broken again? this is useless ({i})",
]
NEGATIVE_TEMPLATES = [
    "thanks, that works great, appreciate the help ({i})",
    "how do I export the report to csv ({i})",
    "the button renders fine now, confirming the fix ({i})",
    "could you summarize this document for me please ({i})",
]


def synthetic(n_per_class: int, seed: int = 0) -> list[LabeledExample]:
    rng = random.Random(seed)
    rows = []
    for i in range(n_per_class):
        rows.append(LabeledExample(rng.choice(POSITIVE_TEMPLATES).format(i=i), 1, "synth"))
        rows.append(LabeledExample(rng.choice(NEGATIVE_TEMPLATES).format(i=i), 0, "synth"))
    return rows


def test_label_noise_flags_flipped_labels():
    rows = synthetic(100)
    flipped = [3, 17, 42, 77, 120, 155]
    for i in flipped:
        rows[i] = LabeledExample(rows[i].text, 1 - rows[i].label, rows[i].source)
    report = label_noise(rows)
    flagged_indices = {f.index for f in report.flagged}
    # The lexical split is obvious, so confident learning should catch most planted flips...
    assert len(flagged_indices & set(flipped)) >= 4
    # ...without flagging a large share of the clean rows.
    assert report.n_flagged <= len(rows) * 0.10
    # A planted flip's suggestion should point back at the original label.
    for f in report.flagged:
        if f.index in flipped:
            assert f.suggested_label == 1 - f.given_label
    assert "flagged for hand review" in report.render()


def test_label_noise_requires_both_classes():
    rows = [LabeledExample(f"text {i}", 0, "synth") for i in range(20)]
    with pytest.raises(ValueError):
        label_noise(rows)


def test_duplicates_within_set():
    texts = [
        "The export button does not work",
        "the export   button does not WORK",  # exact after normalization
        "The export button does not work at all",  # near
        "completely unrelated sentence about the weather in spring",
    ]
    report = duplicates(texts)
    assert report.mode == "within"
    assert report.n_exact == 1
    assert report.n_near >= 1
    assert not report.clean
    near_pairs = [(p.index_a, p.index_b) for p in report.pairs if p.kind == "near"]
    assert any(2 in pair for pair in near_pairs)


def test_duplicates_cross_set_clean_when_disjoint():
    train = ["alpha bravo charlie one", "delta echo foxtrot two"]
    evalset = ["completely different golf hotel india", "juliet kilo lima something else"]
    report = duplicates(train, evalset)
    assert report.mode == "cross"
    assert report.clean


def test_duplicates_cross_set_catches_contamination():
    train = ["I already told you three times, fix the login page"]
    evalset = ["unrelated row entirely", "i already told you three times, fix the login page"]
    report = duplicates(train, evalset)
    assert report.n_exact == 1


def test_shortcut_control_exposes_lexical_giveaway():
    train = synthetic(100, seed=1)
    evalset = [EvalItem(ex.text, ex.label, "synth-eval") for ex in synthetic(50, seed=2)]
    op = shortcut_control(train, evalset, max_fp=0.10)
    # Deliberately shortcut-riddled data: the dumb model should look excellent — that IS the signal.
    assert op.recall >= 0.9
    assert op.fp_rate <= 0.10


def test_prevalence():
    rows = synthetic(10)
    p = prevalence(rows)
    assert (p.n, p.n_pos) == (20, 10)
    assert p.rate == pytest.approx(0.5)
    assert "50.0%" in p.render()
