# SPDX-License-Identifier: Apache-2.0
"""The tool-error harness reaches the SHIPPING detector, and its corpus counts what the detector counts.

Skips when the JVM or the compiled backend is absent, exactly as the metric-drift bridge test does — but
never falls back to a Python restatement of the arithmetic, because a number measured against one of
those describes a detector nobody ships.
"""

from __future__ import annotations

import pytest

from tool_error import bridge, corpus


def _available() -> bool:
    try:
        bridge.classpath()
    except bridge.BridgeUnavailable:
        return False
    return bridge.java_home() is not None


pytestmark = pytest.mark.skipif(not _available(), reason="no JVM or uncompiled backend")


def series(healthy_hours: int, broken_hours: int, calls: int, p0: float, p1: float) -> list[dict]:
    buckets = []
    for h in range(healthy_hours):
        buckets.append({"bucket": f"2026-07-01T{h:02d}:00:00Z", "calls": calls, "failures": round(calls * p0)})
    for h in range(broken_hours):
        buckets.append({"bucket": f"2026-07-02T{h:02d}:00:00Z", "calls": calls, "failures": round(calls * p1)})
    return [{"tool": "search_docs", "buckets": buckets}]


def test_bridge_reaches_the_real_detector():
    out = bridge.replay(series(20, 20, 200, 0.01, 0.05), h=6.0)
    assert len(out) == 1
    assert out[0]["armed"] is True
    assert out[0]["alarm_after_calls"] is not None, "a sustained 5x should alarm"


def test_unmodified_traffic_does_not_alarm():
    out = bridge.replay(series(60, 0, 200, 0.01, 0.01), h=6.0)
    assert out[0]["alarm_after_calls"] is None, "in-control traffic is a false alarm if it fires"


def test_a_higher_threshold_never_alarms_sooner():
    """Monotonicity. If it ever fails, the score or the accumulator is wrong, not the tuning."""
    s = series(20, 40, 200, 0.01, 0.03)
    low = bridge.replay(s, h=5.0)[0]["alarm_after_calls"]
    high = bridge.replay(s, h=12.0)[0]["alarm_after_calls"]
    assert low is not None
    assert high is None or high >= low


def test_a_thin_tool_never_arms():
    out = bridge.replay([{"tool": "rare", "buckets": [{"bucket": "2026-07-01T00:00:00Z", "calls": 10, "failures": 5}]}], h=6.0)
    assert out[0]["armed"] is False, "a wait, not a skip — but nothing is judged against 10 calls"


def test_the_export_sql_carries_the_shipping_failure_predicate():
    """The corpus must count a failure the way ToolFailure does, or it measures a different detector."""
    for fragment in (
        "tc.error_type IS NOT NULL",
        "o.attributes ->> 'error.type'",
        "o.attributes ->> 'exception.type'",
        '{"isError": true}',
    ):
        assert fragment in corpus.FAILURE_PREDICATE, fragment
    assert corpus.FAILURE_PREDICATE.count("(") == corpus.FAILURE_PREDICATE.count(")")
    assert corpus.FAILURE_PREDICATE in corpus.EXPORT_SQL
