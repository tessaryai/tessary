# SPDX-License-Identifier: Apache-2.0
"""The metric-drift harness's own contract: the window rules it mirrors, and the bridge it measures through.

Two halves, deliberately separate:

- The window tests are pure Python and always run. They pin the rules `metric_drift/windows.py`
  copies from `MetricDriftSweep` — the close criteria, the wait-don't-skip floor, the bootstrap pin —
  because those are the part of this harness that can silently disagree with the sweep. A number
  measured under the wrong windowing is a number about a detector nobody runs.
- The bridge test needs a JVM and a compiled backend, and skips without them. It is what proves the
  harness reaches the real `MetricDriftDetector` rather than a restatement of it: if this skips
  everywhere, the eval's central claim is unverified.
"""

from __future__ import annotations

import math

import pytest

from metric_drift import bridge, corpus, windows
from metric_drift.windows import Config, close_windows


def sample(index: int, *, bucket: str = "checkout", hours: float = 0.0, value: float = 1000.0) -> corpus.Sample:
    at = f"2026-06-{1 + int(hours // 24):02d}T{int(hours % 24):02d}:{index % 60:02d}:00Z"
    return corpus.Sample(
        bucket_key=bucket,
        call_site_id=bucket,
        value=value,
        event_at=at,
        created_at=at,
        trace_id=f"tr_{index:05d}",
    )


def test_window_closes_on_count() -> None:
    config = Config(window_target_count=50, min_sample=10, window_max_hours=168)
    closes = close_windows("turn_duration", [sample(i) for i in range(120)], config)
    assert [c.count for c in closes] == [50, 50]
    # The 20 left over stay open. Closing them early would compare a partial window against a full
    # one and report the difference as a shift.
    assert sum(c.count for c in closes) == 100


def test_min_sample_is_a_wait_not_a_skip() -> None:
    """A thin bucket holds its window open past the elapsed horizon rather than closing a window
    nothing can be compared against — PROGRAM.md §2.3, and `shouldClose`'s first line."""
    config = Config(window_target_count=500, min_sample=100, window_max_hours=1)
    rows = [sample(i, hours=i * 2.0) for i in range(40)]  # 80 hours of traffic, 40 samples
    assert close_windows("turn_duration", rows, config) == []

    rows = [sample(i, hours=i * 0.05) for i in range(150)]
    closes = close_windows("turn_duration", rows, config)
    assert len(closes) == 1
    assert closes[0].count >= config.min_sample


def test_elapsed_horizon_closes_a_thin_window_once_it_is_comparable() -> None:
    config = Config(window_target_count=500, min_sample=10, window_max_hours=4)
    closes = close_windows("turn_duration", [sample(i, hours=i) for i in range(12)], config)
    assert closes, "a bucket past min_sample and past the horizon must close on elapsed event time"
    # One sample an hour: the horizon is passed at hour 4, but nothing closes until the sample that
    # brings the window up to min_sample — the floor is tested first, and it is a wait.
    assert closes[0].count == config.min_sample


def test_windows_are_cut_on_event_time_not_ingest_time() -> None:
    """A backfill stamps a whole corpus within minutes of itself on the ingest clock. Cutting on that
    clock would let one window swallow the lot and compare it against nothing (PROGRAM.md §5)."""
    config = Config(window_target_count=500, min_sample=10, window_max_hours=6)
    rows = [sample(i, hours=i) for i in range(20)]
    for row in rows:
        row.created_at = "2026-07-30T09:00:00Z"  # every row ingested in the same burst
    closes = close_windows("turn_duration", rows, config)
    assert len(closes) >= 2, "event time spans 20 hours, so a 6-hour horizon must close several windows"


def test_plan_pins_the_first_armed_close_and_never_re_pins() -> None:
    """The bootstrap pin is the only pin an offline replay performs. An automatic re-pin would let
    the next window normalize an injected regression away, and detection rates would read high for
    the wrong reason."""
    config = Config(window_target_count=20, min_sample=10)
    rows = [sample(i) for i in range(80)]
    plan = windows.plan("turn_duration", rows, config)

    def ref_of(job_id: str) -> str | None:
        return next(job for job in plan.jobs if job.id == job_id).ref

    first, second, third = plan.planned[0], plan.planned[1], plan.planned[2]
    assert first.previous_job is None, "the first close has no previous window to compare against"
    assert ref_of(first.pinned_job) is None
    # Every later close is compared against the SAME pinned window: window 0.
    assert {ref_of(item.pinned_job) for item in (second, third)} == {"turn_duration|checkout|0"}
    # ...and against the window immediately before it.
    assert ref_of(third.previous_job) == "turn_duration|checkout|1"


def test_call_sites_of_a_tool_window_carry_every_entry_point() -> None:
    """A tool bucket is keyed on the tool alone, so its window legitimately draws from several call
    sites — which is exactly what MetricSuppression tests overlap on."""
    rows = [sample(i, bucket="tool:search") for i in range(10)]
    for i, row in enumerate(rows):
        row.call_site_id = "checkout" if i % 2 else "answer-faq"
    closes = close_windows("tool_duration", rows, Config(window_target_count=10, min_sample=2))
    assert set(closes[0].call_sites) == {"checkout", "answer-faq"}


# -------------------------------------------------------------------------------------------------
# The bridge — needs a JVM and the compiled backend modules `bridge.MODULES` names
# -------------------------------------------------------------------------------------------------


def _bridge_available() -> bool:
    try:
        bridge.classpath()
    except bridge.BridgeUnavailable:
        return False
    return bridge.java_home() is not None


requires_bridge = pytest.mark.skipif(
    not _bridge_available(),
    reason="no JVM or no compiled backend — export JAVA_HOME and compile the backend once",
)


@requires_bridge
def test_bridge_reaches_the_real_detector() -> None:
    """A window multiplied by 1.4 reports W1 = ln(1.4), which is the test that proves the statistic.

    It is asserted here rather than trusted because this is the harness's whole claim: the floors the
    null run recommends are floors on THIS number, computed by the shipping MetricHistogram.
    """
    base = [800.0 * math.exp(0.4 * math.sin(i)) for i in range(600)]
    response = bridge.decide(
        [bridge.Sketch("ref", base), bridge.Sketch("cur", [v * 1.4 for v in base])],
        [bridge.Job("j", "turn_duration", "pinned", "cur", "checkout", ref="ref")],
    )
    decision = response.decisions["j"]
    assert decision.fired
    assert decision.w1_log == pytest.approx(math.log(1.4), abs=0.02)
    assert decision.ratio == pytest.approx(1.4, abs=0.03)
    assert decision.cause_key == "turn_duration:checkout:slower:pinned"
    assert decision.silence is None


@requires_bridge
def test_bridge_runs_the_real_suppression_rule() -> None:
    """A tool whose median moved by most of the turn's move explains it (§6.1); one that barely moved
    does not, which is the *eleven tool calls where three used to do* case the rule must not swallow."""
    turn_ref = [4000.0] * 400
    turn_cur = [8000.0] * 400
    dominant_ref = [3000.0] * 400
    dominant_cur = [6800.0] * 400
    flat = [300.0] * 400

    response = bridge.decide(
        [
            bridge.Sketch("tr", turn_ref),
            bridge.Sketch("tc", turn_cur),
            bridge.Sketch("dr", dominant_ref),
            bridge.Sketch("dc", dominant_cur),
            bridge.Sketch("fr", flat),
            bridge.Sketch("fc", [v * 1.05 for v in flat]),
        ],
        [
            bridge.Job("turn", "turn_duration", "pinned", "tc", "checkout", ref="tr"),
            bridge.Job("dominant", "tool_duration", "pinned", "dc", "tool:deep_search", ref="dr"),
            bridge.Job("flat", "tool_duration", "pinned", "fc", "tool:ping", ref="fr"),
        ],
        [
            bridge.SuppressionRequest(
                "with_dominant",
                "turn",
                "checkout",
                ["checkout"],
                tools=[{"job": "dominant", "bucket_key": "tool:deep_search", "call_sites": ["checkout"]}],
            ),
            bridge.SuppressionRequest(
                "with_flat_only",
                "turn",
                "checkout",
                ["checkout"],
                tools=[{"job": "flat", "bucket_key": "tool:ping", "call_sites": ["checkout"]}],
            ),
        ],
    )
    assert response.suppression["with_dominant"]["suppressed_by"] == "dominant"
    assert response.suppression["with_dominant"]["covered"] == pytest.approx(0.95, abs=0.1)
    assert response.suppression["with_flat_only"]["suppressed_by"] is None
