# SPDX-License-Identifier: Apache-2.0
"""framework.agreement — reference-value tests (statistics code earns exact-arithmetic checks)."""

import pytest

from framework import cohen_kappa, disagreements, fleiss_kappa, krippendorff_alpha
from framework.agreement import band_of


def rows_from(pairs):
    """[(a_label, b_label), ...] -> AnnotationRow triples for annotators 'a' and 'b'."""
    out = []
    for i, (a, b) in enumerate(pairs):
        out.append((f"item{i}", "a", a))
        out.append((f"item{i}", "b", b))
    return out


def test_cohen_kappa_hand_computed():
    # a=[1,1,0,0], b=[1,0,0,0]: p_o=3/4; marginals a=(.5,.5), b=(.25,.75) -> p_e=.5; kappa=0.5
    report = cohen_kappa(rows_from([(1, 1), (1, 0), (0, 0), (0, 0)]), "a", "b")
    assert report.value == pytest.approx(0.5)
    assert report.raw_agreement == pytest.approx(0.75)
    assert report.n_items == 4
    assert report.band == "insufficient"


def test_cohen_kappa_perfect_and_constant_is_one_not_nan():
    report = cohen_kappa(rows_from([(0, 0), (0, 0), (0, 0)]), "a", "b")
    assert report.value == 1.0
    assert report.band == "reliable"


def test_cohen_kappa_uses_only_shared_items():
    rows = rows_from([(1, 1), (0, 0)]) + [("solo", "a", 1)]
    report = cohen_kappa(rows, "a", "b")
    assert report.n_items == 2
    assert report.n_dropped == 1


def test_fleiss_kappa_hand_computed():
    # 3 raters, 2 items: item0 = (1,1,0), item1 = (0,0,0).
    # P_i: item0 = (3*2*? ) -> pairs agreeing: 1s: 2*1=2, 0s: 0 -> 2/6; item1 = 6/6 -> raw = (1/3+1)/2 = 2/3
    # p_j: 1s = 2/6, 0s = 4/6 -> P_e = (1/3)^2 + (2/3)^2 = 5/9; kappa = (2/3 - 5/9)/(1 - 5/9) = 0.25
    rows = [
        ("item0", "r1", 1),
        ("item0", "r2", 1),
        ("item0", "r3", 0),
        ("item1", "r1", 0),
        ("item1", "r2", 0),
        ("item1", "r3", 0),
    ]
    report = fleiss_kappa(rows)
    assert report.value == pytest.approx(0.25)
    assert report.raw_agreement == pytest.approx(2 / 3)
    assert report.n_annotators == 3


def test_fleiss_kappa_drops_partially_covered_items():
    rows = [
        ("full", "r1", 1),
        ("full", "r2", 1),
        ("full", "r3", 1),
        ("partial", "r1", 0),
        ("partial", "r2", 0),
    ]
    report = fleiss_kappa(rows)
    assert report.n_items == 1
    assert report.n_dropped == 1
    assert report.value == 1.0  # the one fully-covered item is unanimous


def test_krippendorff_alpha_perfect():
    rows = rows_from([(1, 1), (0, 0), (1, 1)])
    report = krippendorff_alpha(rows)
    assert report.value == 1.0


def test_krippendorff_alpha_tolerates_missing_and_degrades_with_disagreement():
    agree = rows_from([(1, 1), (0, 0), (1, 1), (0, 0)])
    ragged = agree + [("extra", "c", 1)]  # only one rating — excluded from n_items, no crash
    report = krippendorff_alpha(ragged)
    assert report.n_items == 4
    assert report.n_dropped == 1

    disagree = rows_from([(1, 0), (0, 0), (1, 1), (0, 1)])
    assert krippendorff_alpha(disagree).value < report.value


def test_krippendorff_alpha_last_row_wins_on_duplicate_annotations():
    base = rows_from([(1, 0), (0, 0), (1, 1), (0, 1)])
    dup = base + [("item0", "a", 0)]  # annotator 'a' relabels item0: 1 -> 0 (last row wins)
    resolved = rows_from([(0, 0), (0, 0), (1, 1), (0, 1)])
    assert krippendorff_alpha(dup).value == pytest.approx(krippendorff_alpha(resolved).value)
    assert krippendorff_alpha(dup).value != pytest.approx(krippendorff_alpha(base).value)


def test_disagreements_lists_conflicted_items_only():
    rows = rows_from([(1, 1), (1, 0), (0, 1)])
    assert disagreements(rows) == ["item1", "item2"]


def test_bands():
    assert band_of(0.85) == "reliable"
    assert band_of(0.7) == "tentative"
    assert band_of(0.5) == "insufficient"
