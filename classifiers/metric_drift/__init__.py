# SPDX-License-Identifier: Apache-2.0
"""Metric drift — the offline harness for `duration_drift` and `cost_drift`.

Design record: PROGRAM.md; build order: PLAN.md; how to run the three evals: README.md.

Unlike `behavior_drift/`, this module ships **no detector of its own**. The statistic, the sketch and
the suppression rule are the shipping Java classes, reached over `bridge.py`, and what lives here is
the traffic, the window replay and the injection operators. That split is deliberate: it is the
reason the null run can SET `MetricDriftConfig.DEFAULT_W1_FLOOR` rather than merely comment on it.
"""

from .bridge import BridgeUnavailable, Decision, Job, Sketch, SuppressionRequest, decide
from .corpus import COST, TOOL_DURATION, TURN_DURATION, Sample, Turn, load_turns_jsonl, samples
from .windows import Config, Finding, Replay, close_windows, replay, replay_duration

__all__ = [
    "COST",
    "TOOL_DURATION",
    "TURN_DURATION",
    "BridgeUnavailable",
    "Config",
    "Decision",
    "Finding",
    "Job",
    "Replay",
    "Sample",
    "Sketch",
    "SuppressionRequest",
    "Turn",
    "close_windows",
    "decide",
    "load_turns_jsonl",
    "replay",
    "replay_duration",
    "samples",
]
