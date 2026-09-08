# SPDX-License-Identifier: Apache-2.0
"""Metrics and the ship gate — the confidence mechanism.

The gate every classifier must clear is expressed as **recall at a fixed false-positive rate**:
"catch at least ``min_recall`` of the positives while flagging at most ``max_fp`` of the
negatives." This is the honest way to measure an over-defensive model — the exact axis on which
the current jailbreak/ProtectAI head fails.

Given scores in [0,1] and 0/1 labels, we pick the threshold that just meets ``max_fp`` on the
negatives, then read off the recall on the positives at that threshold.
"""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(slots=True)
class Gate:
    """The ship bar: recall >= ``min_recall`` while false-positive rate <= ``max_fp``."""

    min_recall: float
    max_fp: float


@dataclass(slots=True)
class OperatingPoint:
    threshold: float
    recall: float
    fp_rate: float
    precision: float
    n_pos: int
    n_neg: int

    def passes(self, gate: Gate) -> bool:
        return self.fp_rate <= gate.max_fp and self.recall >= gate.min_recall


def operating_point_at_fixed_fp(labels: list[int], scores: list[float], max_fp: float) -> OperatingPoint:
    """Choose the lowest threshold whose FP rate on the negatives is <= ``max_fp``, then measure.

    Lowest-such-threshold maximizes recall subject to the FP ceiling — the standard way to set a
    precision-constrained operating point.
    """
    if len(labels) != len(scores):
        raise ValueError("labels and scores must be the same length")
    pos = [s for s, y in zip(scores, labels) if y == 1]
    neg = [s for s, y in zip(scores, labels) if y == 0]
    n_pos, n_neg = len(pos), len(neg)

    # Candidate thresholds: every distinct score, plus 1.0. For each, FP rate = frac of negatives
    # scoring >= t. Walk from high to low and stop at the last t that still satisfies the ceiling.
    candidates = sorted(set(scores) | {1.0}, reverse=True)
    chosen = 1.0
    for t in candidates:
        fp = _rate_at_or_above(neg, t)
        if fp <= max_fp:
            chosen = t
        else:
            break

    fp_rate = _rate_at_or_above(neg, chosen)
    recall = _rate_at_or_above(pos, chosen)
    tp = sum(1 for s in pos if s >= chosen)
    fp_count = sum(1 for s in neg if s >= chosen)
    precision = tp / (tp + fp_count) if (tp + fp_count) else 0.0
    return OperatingPoint(chosen, recall, fp_rate, precision, n_pos, n_neg)


def _rate_at_or_above(scores: list[float], threshold: float) -> float:
    if not scores:
        return 0.0
    return sum(1 for s in scores if s >= threshold) / len(scores)
