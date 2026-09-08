# SPDX-License-Identifier: Apache-2.0
"""Inter- and intra-annotator agreement — the first row of the dataset-quality bar (PROGRAM.md §1④).

Raw "we agreed 90% of the time" is meaningless when one class dominates: two annotators who always
answer "neutral" agree constantly while conveying nothing. These metrics correct for
chance-level agreement. Bands (Krippendorff's own): **>= 0.8 reliable**, **0.667–0.8 tentative**,
**below 0.667 stop — fix the labeling guide and re-label**, don't train.

Data shape: plain ``(item_id, annotator_id, label)`` rows — the same triple Argilla exports and
the platform's ``annotation`` table stores. Labels are nominal (ints or strings); if the same
annotator labels the same item twice in one row set, the last row wins.

Solo-annotator phase (PROGRAM.md §1③) uses these same functions:
- human-vs-judge:            ``cohen_kappa(rows, "owner", "judge")``
- intra-annotator consistency: record the re-label pass under a distinct annotator id and take
  ``cohen_kappa(rows, "owner", "owner-repass")``

Deps: the ``quality`` extra (scikit-learn, statsmodels, nltk) — imported lazily inside each
function so the core harness installs without them.
"""

from __future__ import annotations

from collections import Counter
from dataclasses import dataclass

Label = int | str
AnnotationRow = tuple[str, str, Label]  # (item_id, annotator_id, label)

STRONG = 0.8
TENTATIVE = 0.667


def band_of(value: float) -> str:
    if value >= STRONG:
        return "reliable"
    if value >= TENTATIVE:
        return "tentative"
    return "insufficient"


@dataclass(slots=True)
class AgreementReport:
    metric: str
    value: float
    raw_agreement: float  # observed (uncorrected) agreement — for context, never the headline
    n_items: int
    n_annotators: int
    n_dropped: int  # items excluded (missing coverage for the metric's requirements)

    @property
    def band(self) -> str:
        return band_of(self.value)

    def render(self) -> str:
        dropped = f"  dropped={self.n_dropped}" if self.n_dropped else ""
        return (
            f"{self.metric}={self.value:.3f} ({self.band})  raw={self.raw_agreement:.3f}  "
            f"items={self.n_items}  annotators={self.n_annotators}{dropped}"
        )


def _pivot(rows: list[AnnotationRow]) -> dict[str, dict[str, Label]]:
    """item_id -> {annotator_id -> label}, last row winning per (item, annotator)."""
    by_item: dict[str, dict[str, Label]] = {}
    for item_id, annotator_id, label in rows:
        by_item.setdefault(item_id, {})[annotator_id] = label
    return by_item


def cohen_kappa(rows: list[AnnotationRow], annotator_a: str, annotator_b: str) -> AgreementReport:
    """Chance-corrected agreement between exactly two annotators, on their shared items."""
    by_item = _pivot(rows)
    pairs = [
        (labels[annotator_a], labels[annotator_b])
        for labels in by_item.values()
        if annotator_a in labels and annotator_b in labels
    ]
    if not pairs:
        raise ValueError(f"no items labeled by both {annotator_a!r} and {annotator_b!r}")
    a, b = zip(*pairs)
    raw = sum(1 for x, y in pairs if x == y) / len(pairs)
    if raw == 1.0:
        # Perfect agreement: kappa's chance term degenerates (NaN) when both annotators are
        # constant; report the honest 1.0 instead.
        value = 1.0
    else:
        from sklearn.metrics import cohen_kappa_score  # lazy — quality extra

        value = float(cohen_kappa_score(a, b))
    return AgreementReport(
        metric=f"cohen_kappa[{annotator_a}|{annotator_b}]",
        value=value,
        raw_agreement=raw,
        n_items=len(pairs),
        n_annotators=2,
        n_dropped=len(by_item) - len(pairs),
    )


def fleiss_kappa(rows: list[AnnotationRow]) -> AgreementReport:
    """Chance-corrected agreement for 3+ annotators.

    Fleiss' formulation needs the same number of ratings on every item; items with fewer ratings
    than the fullest item are dropped and counted in ``n_dropped`` (use ``krippendorff_alpha`` if
    coverage is ragged — that's its actual advantage).
    """
    by_item = _pivot(rows)
    r = max((len(labels) for labels in by_item.values()), default=0)
    if r < 2:
        raise ValueError("fleiss_kappa needs at least 2 ratings on at least one item")
    kept = [labels for labels in by_item.values() if len(labels) == r]
    if not kept:
        raise ValueError("no items with full annotator coverage")
    categories = sorted({label for labels in kept for label in labels.values()}, key=str)
    cat_index = {c: i for i, c in enumerate(categories)}
    table = [[0] * len(categories) for _ in kept]
    for row_idx, labels in enumerate(kept):
        for label in labels.values():
            table[row_idx][cat_index[label]] += 1
    # Observed agreement: mean per-item fraction of agreeing rating pairs.
    raw = sum(sum(c * (c - 1) for c in item_row) / (r * (r - 1)) for item_row in table) / len(table)
    if raw == 1.0:
        value = 1.0  # same degenerate-chance-term case as cohen_kappa
    else:
        from statsmodels.stats.inter_rater import fleiss_kappa as _fleiss  # lazy — quality extra

        value = float(_fleiss(table, method="fleiss"))
    annotators = {annotator for _, annotator, _ in rows}
    return AgreementReport(
        metric="fleiss_kappa",
        value=value,
        raw_agreement=raw,
        n_items=len(kept),
        n_annotators=len(annotators),
        n_dropped=len(by_item) - len(kept),
    )


def krippendorff_alpha(rows: list[AnnotationRow]) -> AgreementReport:
    """Krippendorff's alpha (nominal), any number of annotators, tolerates missing labels.

    The general-purpose metric: use it when annotator coverage is ragged (not every annotator saw
    every item) — the case Fleiss can't handle without dropping items.
    """
    by_item = _pivot(rows)
    multi = {item: labels for item, labels in by_item.items() if len(labels) >= 2}
    if not multi:
        raise ValueError("krippendorff_alpha needs at least one item with 2+ ratings")
    # Observed pairwise agreement over multiply-rated items (context only; alpha is the headline).
    agree_pairs = total_pairs = 0
    for labels in multi.values():
        values = list(labels.values())
        counts = Counter(values)
        n = len(values)
        total_pairs += n * (n - 1) // 2
        agree_pairs += sum(c * (c - 1) // 2 for c in counts.values())
    raw = agree_pairs / total_pairs
    if raw == 1.0:
        value = 1.0  # expected-disagreement denominator degenerates when a single label dominates
    else:
        from nltk.metrics.agreement import AnnotationTask  # lazy — quality extra

        # Feed the deduped pivot, not the raw rows — last-row-wins must hold here too, or a
        # duplicated (item, annotator) row silently changes alpha while raw/n_items don't see it.
        task = AnnotationTask(
            data=[
                (annotator, item, str(label))
                for item, labels in by_item.items()
                for annotator, label in labels.items()
            ]
        )
        value = float(task.alpha())
    annotators = {annotator for _, annotator, _ in rows}
    return AgreementReport(
        metric="krippendorff_alpha",
        value=value,
        raw_agreement=raw,
        n_items=len(multi),
        n_annotators=len(annotators),
        n_dropped=len(by_item) - len(multi),
    )


def disagreements(rows: list[AnnotationRow]) -> list[str]:
    """Item ids whose annotators disagree — the adjudication queue (each ruling goes into the
    labeling guide's borderline-cases section, per the loop's ③→① feedback edge)."""
    return sorted(item for item, labels in _pivot(rows).items() if len(set(labels.values())) > 1)
