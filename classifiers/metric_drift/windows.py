# SPDX-License-Identifier: Apache-2.0
"""The window machinery, replayed — and the one part of this harness that is NOT the shipping code.

`MetricDriftSweep` decides three things: which readings exist (`MetricSource`), which windows they
fall into, and — via `MetricDriftDetector`, `MetricHistogram` and `MetricSuppression` — what a closed
window means. The third is reached over the bridge and is the shipping implementation, byte for byte.
The second is reproduced here, because in the sweep it is entangled with a lease, a keyset cursor, a
repository and a Spring context, and an eval that had to stand up a schema to ask "would this have
fired" is an eval nobody runs often enough to tune with.

**So this file can drift from `MetricDriftSweep` and nothing would fail.** What it copies, and what
each rule is for:

| rule | mirrored from | why it matters to a number this harness reports |
|---|---|---|
| close on count OR elapsed EVENT hours, but only past `min_sample` | `shouldClose` | the floor is a WAIT, not a skip; treating it as a skip would silently drop every thin bucket from the null case |
| windows cut on `event_at`, ordering and watermark on `created_at` | `foldBucket` | on the ingest clock a backfilled corpus collapses into one window and is compared against nothing |
| both references every close, pinned finding preferred, at most one finding per close | `compareAndPin` | reporting both would double every firing in the null count |
| the first armed close bootstraps the pin; nothing else ever re-pins | `compareAndPin` | an automatic re-pin would let the next window normalize an injected regression away, and detection rates would read high for the wrong reason |

The one thing deliberately not modelled is the human correction loop: *Legitimate — absorb* moves the
pinned reference, and there is no human in an offline replay to press it. A persisting shift therefore
re-fires here on every close, which is exactly what the product does until somebody absorbs it.
"""

from __future__ import annotations

from collections import Counter
from dataclasses import dataclass, field
from datetime import datetime

from . import bridge
from .corpus import GRID_OF, Sample, by_bucket


@dataclass(frozen=True)
class Config:
    """The operating point, named as `MetricDriftConfig` names it.

    `w1_floor` is a guess until the null run sets it, and this dataclass will not pretend otherwise:
    the default is the same 0.18 the Java record carries with the same warning attached.
    """

    window_target_count: int = 500
    window_max_hours: int = 168
    min_sample: int = 150
    w1_floor: float = 0.18
    hist_bins: int = 320
    explained_by_fraction: float = 0.5


@dataclass
class ClosedWindow:
    """One window that met its close criteria, with everything a comparison or a report needs."""

    measure: str
    bucket_key: str
    index: int
    rows: list[Sample]
    opened_at: str
    closed_at: str

    @property
    def values(self) -> list[float]:
        return [row.value for row in self.rows]

    @property
    def count(self) -> int:
        return len(self.rows)

    @property
    def call_sites(self) -> Counter:
        """Which entry points this window's traffic came in through.

        One key at turn grain, where the bucket IS a call site. As many as dispatched the tool at
        tool grain, where the bucket is an `ActionSymbol` alone — a tool's latency is a tool's
        latency whichever entry point called it, and scoping the bucket per call site would shatter a
        shared tool into populations none of which are thick enough to arm.
        """
        return Counter(row.call_site_id for row in self.rows)

    @property
    def last_version_id(self) -> str | None:
        return self.rows[-1].project_version_id if self.rows else None


@dataclass
class Finding:
    """What one close earned: at most one, pinned view preferred (PROGRAM.md §6.1)."""

    measure: str
    bucket_key: str
    window: ClosedWindow
    decision: bridge.Decision
    #: The deploy the shift is measured SINCE: the version the reference was pinned under for a
    #: pinned finding, and the closing window's own version for a previous-window one. A shift
    #: measured against the window pinned at a deploy is a shift since THAT deploy.
    since_version_id: str | None
    suppressed_by: str | None = None
    covered: float | None = None

    @property
    def cause_key(self) -> str:
        return self.decision.cause_key


@dataclass
class Replay:
    """One measure's whole replay: every close, every comparison, and the findings that survived."""

    measure: str
    closes: list[ClosedWindow]
    findings: list[Finding]
    #: Every decision the run made, keyed by job id — including the silent ones, which is where the
    #: reason for a quiet run lives (`BELOW_MIN_SAMPLE` on every close reads very differently from
    #: `WITHIN_FLOOR` on every close).
    decisions: dict[str, bridge.Decision] = field(default_factory=dict)
    edges: dict[str, dict] = field(default_factory=dict)

    @property
    def comparisons(self) -> int:
        """Closes that were actually compared against something — the denominator of a false-positive
        rate. A close with no reference yet is not a chance to be wrong."""
        return sum(1 for d in self.decisions.values() if d.silence != "NO_REFERENCE")

    def silence_counts(self) -> Counter:
        return Counter(d.silence or "FIRED" for d in self.decisions.values())


# -------------------------------------------------------------------------------------------------
# Window assembly
# -------------------------------------------------------------------------------------------------


def close_windows(measure: str, rows: list[Sample], config: Config) -> list[ClosedWindow]:
    """Walk one bucket's readings in ingest order, closing windows as the criteria are met.

    The tail — whatever is left open when the corpus runs out — is deliberately dropped rather than
    closed early. In production that window is still filling; closing it here would compare a partial
    window against a full one and report the difference as a shift.
    """
    closes: list[ClosedWindow] = []
    open_rows: list[Sample] = []
    opened_at: str | None = None
    for row in rows:
        if opened_at is None:
            opened_at = row.event_at
        open_rows.append(row)
        if not _should_close(len(open_rows), opened_at, row.event_at, config):
            continue
        closes.append(
            ClosedWindow(
                measure=measure,
                bucket_key=row.bucket_key,
                index=len(closes),
                rows=open_rows,
                opened_at=opened_at,
                closed_at=row.event_at,
            )
        )
        open_rows = []
        opened_at = None
    return closes


def _should_close(count: int, opened_at: str, event_at: str, config: Config) -> bool:
    """`MetricDriftSweep.shouldClose`, rule for rule.

    The minimum sample is tested first and is a WAIT: a bucket under it holds its window open past
    the elapsed horizon rather than closing one nothing can be compared against, so a tool called
    thirty times a week is watched on a slower clock instead of never being watched.
    """
    if count < config.min_sample:
        return False
    return count >= config.window_target_count or _elapsed_hours(opened_at, event_at) >= config.window_max_hours


def _elapsed_hours(opened_at: str, event_at: str) -> float:
    """Hours between two event stamps, or 0 when either cannot be parsed or they run backwards.

    Parsed as instants, never compared as strings: rendered timestamps are variable-length (a
    trailing-zero-eliding fractional second), so lexical order diverges from chronological. Backwards
    is not an error — event time genuinely goes backwards inside a backfilled page — it just means
    this sample cannot extend the window's span.
    """
    try:
        start = datetime.fromisoformat(opened_at)
        end = datetime.fromisoformat(event_at)
    except ValueError:
        return 0.0
    elapsed = (end - start).total_seconds() / 3600.0
    return max(elapsed, 0.0)


# -------------------------------------------------------------------------------------------------
# Planning — every comparison a replay will make, decided before any decision exists
# -------------------------------------------------------------------------------------------------


@dataclass
class _Planned:
    """A close and the two jobs it earns, plus the pin state at the moment it closed."""

    window: ClosedWindow
    pinned_job: str
    previous_job: str | None
    pinned_version_id: str | None


@dataclass
class Plan:
    """What to ask the bridge for one measure, and how to read the answer back.

    Built without consulting a single decision, which is possible because pinning is a BOOTSTRAP and
    never an absorb: which window is a given close's reference depends only on counts, not on what
    fired. If that ever stops being true on the Java side, this planning step is what has to change.
    """

    measure: str
    sketches: list[bridge.Sketch]
    jobs: list[bridge.Job]
    planned: list[_Planned]


def plan(measure: str, rows: list[Sample], config: Config, *, floor: float | None = None, prefix: str = "") -> Plan:
    sketches: list[bridge.Sketch] = []
    jobs: list[bridge.Job] = []
    planned: list[_Planned] = []
    grid = GRID_OF[measure]
    w1_floor = config.w1_floor if floor is None else floor

    for bucket_key, bucket_rows in by_bucket(rows).items():
        pinned_id: str | None = None
        pinned_version: str | None = None
        previous_id: str | None = None
        for window in close_windows(measure, bucket_rows, config):
            window_id = f"{prefix}{measure}|{bucket_key}|{window.index}"
            sketches.append(bridge.Sketch(window_id, window.values, grid=grid, bins=config.hist_bins))

            pinned_job = f"{window_id}|pinned"
            jobs.append(
                bridge.Job(
                    id=pinned_job,
                    measure=measure,
                    reference="pinned",
                    ref=pinned_id,
                    cur=window_id,
                    bucket_key=bucket_key,
                    w1_floor=w1_floor,
                    min_sample=config.min_sample,
                    bins=config.hist_bins,
                )
            )
            previous_job = None
            if previous_id is not None:
                previous_job = f"{window_id}|previous"
                jobs.append(
                    bridge.Job(
                        id=previous_job,
                        measure=measure,
                        reference="previous",
                        ref=previous_id,
                        cur=window_id,
                        bucket_key=bucket_key,
                        w1_floor=w1_floor,
                        min_sample=config.min_sample,
                        bins=config.hist_bins,
                    )
                )
            planned.append(_Planned(window, pinned_job, previous_job, pinned_version))

            # The bootstrap pin, and the ONLY pin this harness performs. A window too thin to be
            # compared is too thin to become the bar everything else is compared against, so it
            # rotates into `previous` and the bucket keeps waiting.
            if pinned_id is None and window.count >= config.min_sample:
                pinned_id = window_id
                pinned_version = window.last_version_id
            previous_id = window_id

    return Plan(measure, sketches, jobs, planned)


def findings_of(plan_: Plan, decisions: dict[str, bridge.Decision]) -> list[Finding]:
    """One close, at most one finding, pinned view preferred.

    Two references are two views of one window, not two events. The pinned comparison wins because it
    is the one a human can act on and the one that persists — a step change fires against the
    previous window exactly once and then never again, since the next window's previous IS the new
    level. The previous-window comparison still opens a finding on its own when the pinned one is
    silent, which covers the recovery case and the case where nothing has been pinned yet.
    """
    out: list[Finding] = []
    for item in plan_.planned:
        pinned = decisions.get(item.pinned_job)
        previous = decisions.get(item.previous_job) if item.previous_job else None
        if pinned is not None and pinned.fired:
            out.append(
                Finding(
                    measure=plan_.measure,
                    bucket_key=item.window.bucket_key,
                    window=item.window,
                    decision=pinned,
                    since_version_id=item.pinned_version_id,
                )
            )
        elif previous is not None and previous.fired:
            out.append(
                Finding(
                    measure=plan_.measure,
                    bucket_key=item.window.bucket_key,
                    window=item.window,
                    decision=previous,
                    since_version_id=item.window.last_version_id,
                )
            )
    return out


def replay(measure: str, rows: list[Sample], config: Config, *, floor: float | None = None) -> Replay:
    """Plan, decide over the bridge, and read the findings back. One JVM start."""
    plan_ = plan(measure, rows, config, floor=floor)
    if not plan_.jobs:
        return Replay(measure, [p.window for p in plan_.planned], [])
    response = bridge.decide(plan_.sketches, plan_.jobs)
    return Replay(
        measure=measure,
        closes=[p.window for p in plan_.planned],
        findings=findings_of(plan_, response.decisions),
        decisions=response.decisions,
        edges=response.edges,
    )


# -------------------------------------------------------------------------------------------------
# Both duration grains together — the only way §6.1's suppression rule can be exercised
# -------------------------------------------------------------------------------------------------


@dataclass
class DurationReplay:
    turn: Replay
    tool: Replay
    #: Turn findings the tool grain accounted for. They are not lost: in the product they ride on the
    #: tool finding as evidence, and the tool row is the one that names a fix.
    suppressed: list[Finding]

    @property
    def emitted(self) -> list[Finding]:
        return self.tool.findings + [f for f in self.turn.findings if f.suppressed_by is None]


def replay_duration(
    turn_rows: list[Sample], tool_rows: list[Sample], config: Config, *, floor: float | None = None
) -> DurationReplay:
    """Replay both duration measures in one pass and run the real `MetricSuppression` over the result.

    **Which closes are offered to the rule together is this harness's judgement.** The sweep pairs
    whatever rotated inside one page, and a replay has no pages, so the pairing here is by overlap in
    EVENT time: a tool close whose window overlaps a turn close's window is a candidate to explain it.
    That is the same claim the sweep's page-scoped pairing makes — these two windows saw the same
    stretch of traffic — expressed in the only clock a replay has. What "explains" MEANS is not
    decided here: the coverage arithmetic, the direction test and the call-site overlap are all
    `MetricSuppression.explain`, over the bridge.
    """
    turn_plan = plan("turn_duration", turn_rows, config, floor=floor, prefix="t/")
    tool_plan = plan("tool_duration", tool_rows, config, floor=floor, prefix="o/")

    requests: list[bridge.SuppressionRequest] = []
    for item in turn_plan.planned:
        candidates = []
        for tool_item in tool_plan.planned:
            if not _overlaps(item.window, tool_item.window):
                continue
            # Both of the tool close's jobs are offered: whichever of them fired is the shift, and
            # the bridge drops the ones that did not.
            for job in (tool_item.pinned_job, tool_item.previous_job):
                if job is None:
                    continue
                candidates.append(
                    {
                        "job": job,
                        "bucket_key": tool_item.window.bucket_key,
                        "call_sites": list(tool_item.window.call_sites),
                    }
                )
        if not candidates:
            continue
        for job in (item.pinned_job, item.previous_job):
            if job is None:
                continue
            requests.append(
                bridge.SuppressionRequest(
                    id=f"s|{job}",
                    turn_job=job,
                    turn_bucket_key=item.window.bucket_key,
                    turn_call_sites=list(item.window.call_sites),
                    tools=candidates,
                    explained_by_fraction=config.explained_by_fraction,
                )
            )

    response = bridge.decide(
        turn_plan.sketches + tool_plan.sketches, turn_plan.jobs + tool_plan.jobs, requests
    )

    turn_replay = Replay(
        "turn_duration",
        [p.window for p in turn_plan.planned],
        findings_of(turn_plan, response.decisions),
        {k: v for k, v in response.decisions.items() if k.startswith("t/")},
        response.edges,
    )
    tool_replay = Replay(
        "tool_duration",
        [p.window for p in tool_plan.planned],
        findings_of(tool_plan, response.decisions),
        {k: v for k, v in response.decisions.items() if k.startswith("o/")},
        response.edges,
    )

    suppressed: list[Finding] = []
    for finding in turn_replay.findings:
        verdict = response.suppression.get(f"s|{finding.decision.id}")
        if verdict and verdict.get("suppressed_by"):
            finding.suppressed_by = verdict.get("suppressed_by_bucket")
            finding.covered = verdict.get("covered")
            suppressed.append(finding)
    return DurationReplay(turn_replay, tool_replay, suppressed)


def _overlaps(a: ClosedWindow, b: ClosedWindow) -> bool:
    return not (b.closed_at < a.opened_at or a.closed_at < b.opened_at)
