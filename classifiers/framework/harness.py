# SPDX-License-Identifier: Apache-2.0
"""The eval harness — the shared instrument that turns a classifier into a number.

Give it a ``Scorer``, an eval set, and a ``Gate``; it scores every item, computes the operating
point at the gate's fixed FP rate, breaks the metrics down by slice, and returns a report with a
pass/fail verdict. This is the single tool used to baseline an off-the-shelf head, decide whether
to train our own, and gate every promotion up the lifecycle ladder.

No classifier ships without clearing its gate here.
"""

from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass, field

from .context import render_input
from .metrics import Gate, OperatingPoint, operating_point_at_fixed_fp
from .schema import EvalItem
from .scorer import Scorer


@dataclass(slots=True)
class SliceReport:
    name: str
    value: str
    op: OperatingPoint


@dataclass(slots=True)
class EvalReport:
    classifier: str
    n: int
    overall: OperatingPoint
    gate: Gate
    passed: bool
    slices: list[SliceReport] = field(default_factory=list)

    def render(self) -> str:
        o = self.overall
        head = (
            f"{self.classifier}: {'PASS' if self.passed else 'FAIL'}  "
            f"(gate: recall>={self.gate.min_recall:.2f} @ fp<={self.gate.max_fp:.2f})\n"
            f"  n={self.n}  pos={o.n_pos}  neg={o.n_neg}\n"
            f"  recall={o.recall:.3f}  fp_rate={o.fp_rate:.3f}  precision={o.precision:.3f}  "
            f"threshold={o.threshold:.3f}"
        )
        lines = [head]
        if self.slices:
            lines.append("  slices:")
            for s in sorted(self.slices, key=lambda x: (x.name, x.value)):
                lines.append(
                    f"    {s.name}={s.value:<18} recall={s.op.recall:.3f} fp={s.op.fp_rate:.3f} "
                    f"(pos={s.op.n_pos} neg={s.op.n_neg})"
                )
        return "\n".join(lines)


class EvalHarness:
    def __init__(self, classifier: str, gate: Gate):
        self.classifier = classifier
        self.gate = gate

    def evaluate(self, scorer: Scorer, evalset: list[EvalItem], slice_keys: tuple[str, ...] = ()) -> EvalReport:
        # Score the context contract's rendered thread, not the bare final message. ``render_input``
        # degrades to the bare message when ``context == ""`` (every single-turn item, and every refusal
        # item), so this is a no-op there and only changes context-dependent frustration rows.
        #
        # KNOWN SKEW — this is the FULL thread, and serving may narrow it. A head can opt into a
        # ``ContextPolicy`` (frustration does: last exchange only, assistant prose stubbed), and this
        # harness does not apply one, so a frustration number produced here is measured on more context
        # than production sends. ``window_by_user_turns``/``stub_assistants`` cannot simply be applied
        # here: ``EvalItem.context`` is a pre-RENDERED string, and assistant prose may contain newlines,
        # so recovering turn boundaries by splitting lines is not sound. Closing this needs EvalItem to
        # carry structured turns and narrow before rendering — a dataset-schema change, deliberately not
        # bundled into the serving fix. Until then, narrow at dataset-build time (as the policy-gpt
        # measurement did) rather than trusting a context-dependent number straight out of this harness.
        texts = [render_input(item.context, item.text) for item in evalset]
        labels = [item.label for item in evalset]
        scores = scorer(texts)

        overall = operating_point_at_fixed_fp(labels, scores, self.gate.max_fp)

        slices: list[SliceReport] = []
        for key in slice_keys:
            groups: dict[str, list[int]] = defaultdict(list)
            for idx, item in enumerate(evalset):
                if key in item.slices:
                    groups[item.slices[key]].append(idx)
            for value, idxs in groups.items():
                sub_labels = [labels[i] for i in idxs]
                sub_scores = [scores[i] for i in idxs]
                if sum(sub_labels) == 0 or sum(1 for y in sub_labels if y == 0) == 0:
                    continue  # a slice needs both classes to be meaningful
                slices.append(SliceReport(key, value, operating_point_at_fixed_fp(sub_labels, sub_scores, self.gate.max_fp)))

        return EvalReport(
            classifier=self.classifier,
            n=len(evalset),
            overall=overall,
            gate=self.gate,
            passed=overall.passes(self.gate),
            slices=slices,
        )
