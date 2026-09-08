# SPDX-License-Identifier: Apache-2.0
# HARD BLOCK: importing the framework installs the Bedrock kill-switch (see no_bedrock.py).
# Kept as the first statement so no sibling import can construct a Bedrock client before it.
from . import no_bedrock as _no_bedrock  # noqa: F401  (import for side effect)

"""Shared framework for the default-classifier modules: schema, judge, metrics, harness, scorers,
agreement, audit."""

from .agreement import AgreementReport, cohen_kappa, disagreements, fleiss_kappa, krippendorff_alpha
from .audit import DupReport, NoiseReport, Prevalence, duplicates, label_noise, prevalence, shortcut_control
from .context import (
    cap_assistant,
    error_snippet,
    ASSISTANT_STUB,
    reduce_thread,
    render_context,
    render_input,
    stub_assistants,
    window_by_user_turns,
    render_serving_v0,
    tool_marker,
)
from .harness import EvalHarness, EvalReport
from .judge import ClaudeCliJudge, Judge, LabelSpec, Verdict, classify, default_judge
from .metrics import Gate, OperatingPoint, operating_point_at_fixed_fp
from .schema import EvalItem, LabeledExample, read_evalset, read_examples, write_jsonl
from .scorer import ClassifyServiceScorer, LocalHFScorer, Scorer

__all__ = [
    "AgreementReport",
    "cohen_kappa",
    "fleiss_kappa",
    "krippendorff_alpha",
    "disagreements",
    "render_context",
    "render_input",
    "render_serving_v0",
    "reduce_thread",
    "ASSISTANT_STUB",
    "window_by_user_turns",
    "stub_assistants",
    "cap_assistant",
    "error_snippet",
    "tool_marker",
    "ClaudeCliJudge",
    "DupReport",
    "NoiseReport",
    "Prevalence",
    "duplicates",
    "label_noise",
    "prevalence",
    "shortcut_control",
    "EvalHarness",
    "EvalReport",
    "Judge",
    "LabelSpec",
    "Verdict",
    "classify",
    "default_judge",
    "Gate",
    "OperatingPoint",
    "operating_point_at_fixed_fp",
    "EvalItem",
    "LabeledExample",
    "read_evalset",
    "read_examples",
    "write_jsonl",
    "ClassifyServiceScorer",
    "LocalHFScorer",
    "Scorer",
]
