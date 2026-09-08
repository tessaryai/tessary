# SPDX-License-Identifier: Apache-2.0
"""Where the traffic comes from, and what one reading looks like.

Two sources, one shape:

- `load_turns_jsonl` — a corpus exported from the platform's own substrate with `TURN_EXPORT_SQL` +
  `TOOL_EXPORT_SQL` below. This is the real thing, and it is the ONLY source whose null run can set
  `w1_floor`: the point of that run is to measure how often the detector fires on traffic nobody
  touched, which is a fact about production traffic and not about a generator.
- `synthetic` — lognormal traffic with the shape of an agent product, for developing the harness and
  for smoke-testing a change without a database. Every entry point that uses it prints a banner
  saying so, because a false-positive rate measured against a generator is a property of the
  generator.

The export queries mirror `MetricSourceRepository`'s reads (column-preferred, root span's own
interval, never a min/max envelope) closely enough to produce the same numbers, and are deliberately
not a copy of them: the repository's job includes paging, watermarks and abstention accounting, and
this one's is to dump a corpus once.

Two things the SQL deliberately does NOT do, because doing them here would put the eval's buckets and
dollars on a different footing from the sweep's, and a false-positive rate measured over a different
population is not the shipped detector's false-positive rate:

- **Tool bucket keys** are `ActionSymbol.of(kind, name, false)`, not `kind || ':' || name`. That
  reduction lowercases, strips uuids / hex blobs / digit runs / a trailing numeric id, collapses
  non-alphanumeric runs, and buckets `retrieval` / `embedding` / `reranker` names to their leading path
  segment — so `retrieval:vecs/policy-2024.pdf` and `retrieval:vecs/handbook-2025.pdf` are ONE
  production bucket, and so are `tool:search_docs_3` and `tool:search_docs_4`. Reproducing that in SQL
  is not worth attempting; the export ships `kind` and `name` raw and `_resolve` mints the symbols
  through the shipping Java class.
- **Cost** is summed over llm leaves through `TokenUsage.nonOverlapping()` and priced through
  `TokenPriceBook`. Both live in the JVM, and both matter: the raw blob keys lie about their own
  semantics across providers, and an unpriced model is unpriced rather than free. The export ships the
  leaves' `(model, usage)` pairs and `_resolve` prices them, so `cost_usd` is null exactly where the
  sweep would abstain.

Both resolutions ride one batched `bridge` call at load time. `write_turns_jsonl` round-trips the
RESOLVED values, so a corpus written back out loads again without a JVM.
"""

from __future__ import annotations

import json
import math
import random
from dataclasses import dataclass, field
from pathlib import Path

#: Turn-grain readings. Column-preferred exactly as PROGRAM.md §3.0 requires — `trace.latency_ms`
#: when it is populated, the ROOT SPAN's own interval when it is not, which is what production looks
#: like today (StructuralEnricher passes literal null for every rollup). `max(ended_at) -
#: min(started_at)` is deliberately not used anywhere: measured against production that envelope
#: inflated p95 by ~10%, because async children outlive their parent.
#:
#: Unfinished traces come back with a null duration and are exported anyway. They are a counted
#: category, not a row to drop — excluding them removes precisely the traffic a duration detector
#: most wants to see, and does it invisibly.
TURN_EXPORT_SQL = """
SELECT tr.id                                            AS trace_id,
       tr.context_id                                    AS context_id,
       tr.created_at                                    AS created_at,          -- INGEST clock
       COALESCE(tr.started_at, tr.created_at)            AS event_at,            -- EVENT clock
       -- COALESCEd onto the SESSION's version exactly as SELECT_TRACE_HEAD does, and not because it
       -- is tidier: a producer that stamps its release on the session and not on every trace leaves
       -- `trace.project_version_id` null, and run 3 would then have no deploy to attribute a
       -- regression to on precisely the corpus most worth replaying.
       COALESCE(tr.project_version_id, s.project_version_id) AS project_version_id,
       COALESCE(root.environment_id, tr.environment_id) AS environment_id,
       COALESCE(cs.call_site_id, '__unattributed__')    AS call_site_id,
       COALESCE(tr.latency_ms,
                EXTRACT(EPOCH FROM (root.ended_at - root.started_at)) * 1000) AS duration_ms,
       -- Column-preferred, exactly as MetricSource is: `trace.total_cost` when it holds a value, and
       -- otherwise the priced leaf sum `_resolve` computes from `usage_leaves` below. It is null on
       -- every production row today (StructuralEnricher passes literal null), which is precisely why
       -- the leaves have to ship — a corpus with no priced cost cannot run PLAN.md §9.1's null case for
       -- cost_drift, and cost_drift's w1_floor would stay a guess behind a run that structurally could
       -- not produce its number.
       tr.total_cost                                    AS cost_usd,
       -- The llm leaves' (model, usage) pairs, priced and summed on the Java side through
       -- TokenUsage.nonOverlapping() + TokenPriceBook. Scoped to kind='llm' alone, as MetricSource is:
       -- an agent span carries the CUMULATIVE usage of its subtree, so summing across kinds roughly
       -- doubles every figure. The blob is shipped RAW rather than pre-summed in SQL because the key
       -- names lie about their own semantics across providers — an OpenAI generation arrives wearing
       -- Anthropic key names while still carrying a cache-INCLUSIVE input count, and nothing in SQL can
       -- tell the two families apart. Adding them here would bill cache reads twice.
       leaves.usage_leaves                              AS usage_leaves,
       length(root.input)                               AS user_msg_chars
FROM trace tr
-- Both joins are INNER, as SELECT_TRACE_HEAD's are, and that is a statement about the POPULATION
-- rather than about the columns they bring: a trace with no context row is never returned by
-- `tracesAfter`, so the sweep has never seen one. Exporting them would put findings-per-comparison
-- over a denominator production does not have.
JOIN context tn ON tn.id = tr.context_id
JOIN context s  ON s.id  = subpath(tn.path, 0, 1)::text
-- The BUCKET KEY's call site, resolved exactly as BehaviorSubstrateRepository.SELECT_TRACE_HEAD
-- resolves it and for the reason PROGRAM.md §2.1 states as an imperative: root-span-FIRST, then the
-- producer's own ordering, then wall clock — falling back to the earliest TAGGED child when no root
-- carries one. `seq` is NULL on every OTLP-ingested observation, and a root span that carries no
-- call_site_id while its children do is the ordinary OTLP shape, so a root-only read files those
-- traces under `__unattributed__` — which the turn grain then drops. Measured when the fallback chain
-- was missing: 443 traces whose roots all carried one call site were scattered across six.
LEFT JOIN LATERAL (
    SELECT o.call_site_id
      FROM observation o
     WHERE o.project_id = tr.project_id
       AND o.trace_id = tr.id
       AND COALESCE(o.is_deleted, false) = false
       AND o.call_site_id IS NOT NULL
     ORDER BY (o.parent_observation_id IS NULL) DESC,
              o.seq ASC NULLS LAST, o.started_at ASC NULLS LAST, o.created_at ASC, o.id ASC
     LIMIT 1
) cs ON TRUE
-- The DURATION's root span, which is a different question and needs a different lateral: the
-- earliest-STARTING parentless span, whose own interval is the turn's duration. Never
-- `max(ended_at) - min(started_at)` over the trace — measured against production that envelope
-- inflated p95 by ~10%, because async children outlive their parent.
LEFT JOIN LATERAL (
    SELECT o.id, o.environment_id, o.started_at, o.ended_at, o.input
      FROM observation o
     WHERE o.project_id = tr.project_id
       AND o.trace_id = tr.id
       AND COALESCE(o.is_deleted, false) = false
       AND o.parent_observation_id IS NULL
       -- A null parent means "root" only when the PRODUCER stated no parent. An OTLP batch exporter
       -- flushes on span end, so a root span always ships after the children that outlived it, and
       -- ingest inserts those children with a null parent_observation_id and the stated parent kept
       -- in parent_external_span_id (0035, relinkLateParents). Without this clause the earliest
       -- such child passes for the root, and a 30-second turn exported mid-flight reports the
       -- 1-second child that happened to land first. `turnFacts` carries the clause, so an export
       -- without it feeds this harness durations the sweep never computes — biased SHORT, and worse,
       -- biased short in proportion to how long the turn is. That is the direction that makes a
       -- slowdown read as less of one, which is the one bias a duration detector cannot absorb.
       AND o.parent_external_span_id IS NULL
     ORDER BY o.started_at ASC NULLS LAST, o.created_at ASC, o.id ASC
     LIMIT 1
) root ON TRUE
LEFT JOIN LATERAL (
    SELECT json_agg(json_build_object('model', o.model, 'usage', o.usage)
                    ORDER BY o.started_at ASC NULLS LAST, o.id ASC) AS usage_leaves
      FROM observation o
     WHERE o.trace_id = tr.id
       AND COALESCE(o.is_deleted, false) = false
       AND o.kind = 'llm'
       AND o.usage IS NOT NULL
) leaves ON TRUE
WHERE tr.project_id = :project_id
  AND COALESCE(tr.is_deleted, false) = false
  -- `tracesAfter`'s settle window, which exists because a `trace` row appears as soon as its FIRST
  -- span lands. The cursor is monotonic, so a trace held back for being too young is swept later,
  -- never skipped — but an export taken without this clause catches the newest few minutes of
  -- traffic in a shape the sweep only ever sees after it is complete.
  AND tr.created_at <= now() - make_interval(secs => :settle_seconds)
  -- The stretch to export. `-infinity` takes everything; `export_corpus.py --since` narrows it,
  -- because run 2 ships every sample of every bucket to the JVM once per (operator × call site) and
  -- a year of a busy project is a lot of JSON to pay for a detection rate that a few weeks measures.
  AND tr.created_at >= :since
ORDER BY tr.created_at, tr.id
"""

#: Tool-grain readings, one row per dispatchable span. The kind filter is the sweep's `MEASURED_KINDS`
#: and the exclusions matter: `agent`/`workflow` spans enclose the turn, so their duration tracks the
#: root's and they would "explain" every turn shift under the §6.1 suppression rule; `llm` collapses
#: to one `llm:answer` symbol across the whole project, which is the mixture §2.4 rejects.
TOOL_EXPORT_SQL = """
SELECT o.trace_id                                                        AS trace_id,
       -- kind and name RAW, not `kind || ':' || name`. The sweep's bucket key is
       -- ActionSymbol.of(kind, name, false), whose normalization is not expressible in SQL worth
       -- writing: it lowercases, strips uuids / 16+ char hex blobs / digit runs of 2+ / a trailing
       -- numeric id, collapses every non-alphanumeric run to `_`, and — for retrieval, embedding and
       -- reranker — replaces the name with its corpus bucket (leading path segment). A raw key
       -- shatters production's buckets: `retrieval:vecs/policy-2024.pdf` and
       -- `retrieval:vecs/handbook-2025.pdf` are ONE bucket in the sweep (`retrieval:vecs`) and two
       -- here; so are `tool:search_docs_3` and `tool:search_docs_4`. Thinner buckets arm less often and
       -- are noisier when they do, and both effects move the false-positive count the null run exists
       -- to report — which is the number that replaces the guessed w1_floor. `_resolve` mints these
       -- through the shipping class instead. The name resolution below IS the sweep's verbatim —
       -- normalized tool_call.name, then the raw gen_ai.tool.name attribute (ingest mints a tool_call
       -- row only for kind 'tool', so an MCP call has none), then the span name.
       o.kind                                                            AS kind,
       COALESCE(tcn.name, o.attributes->>'gen_ai.tool.name', o.name)     AS name,
       COALESCE(o.call_site_id, '__unattributed__')                      AS call_site_id,
       COALESCE(o.started_at, o.created_at)                              AS event_at,
       COALESCE(o.latency_ms,
                EXTRACT(EPOCH FROM (o.ended_at - o.started_at)) * 1000)  AS duration_ms
FROM observation o
JOIN trace tr ON tr.id = o.trace_id
LEFT JOIN LATERAL (
    SELECT tc.name FROM tool_call tc
     WHERE tc.observation_id = o.id AND COALESCE(tc.is_deleted, false) = false AND tc.name IS NOT NULL
     ORDER BY tc.started_at ASC NULLS LAST, tc.created_at ASC, tc.id ASC LIMIT 1
) tcn ON TRUE
WHERE tr.project_id = :project_id
  AND COALESCE(o.is_deleted, false) = false
  AND o.kind IN ('tool', 'mcp', 'retrieval', 'embedding', 'reranker')
  -- The same two bounds the turn query carries, so the two pages describe one stretch of traffic.
  -- A tool span whose turn fell outside them is dropped by the loader rather than counted as a
  -- bucket of its own, but exporting it would still be several million rows of nothing.
  AND tr.created_at <= now() - make_interval(secs => :settle_seconds)
  AND tr.created_at >= :since
ORDER BY tr.created_at, tr.id, o.started_at
"""


@dataclass
class ToolCall:
    """One dispatchable span. `bucket_key` is the `ActionSymbol` form, `kind:normalized-name`.

    A row exported by `TOOL_EXPORT_SQL` carries `kind` and `name` instead and leaves `bucket_key`
    empty; `_resolve` fills it from the shipping `ActionSymbol` at load time. A hand-written fixture or
    a round-tripped corpus supplies `bucket_key` directly and needs no JVM.
    """

    bucket_key: str
    duration_ms: float | None
    event_at: str
    call_site_id: str
    #: The raw pair `bucket_key` is minted from, kept only until `_resolve` has run.
    kind: str | None = None
    name: str | None = None


@dataclass
class Turn:
    """One trace, carrying both clocks and every measure the three runs read.

    Both timestamps are kept because they do different jobs and collapsing them is PROGRAM.md §5's
    named failure: windows are cut on `event_at` (a backfill lands a month of traffic in minutes, so
    on the ingest clock one window would swallow the corpus), while ordering and the watermark run on
    `created_at`, which is what is monotonic and gap-free.
    """

    trace_id: str
    created_at: str
    event_at: str
    call_site_id: str
    duration_ms: float | None = None
    cost_usd: float | None = None
    project_version_id: str | None = None
    environment_id: str | None = None
    context_id: str | None = None
    input_tokens: int | None = None
    output_tokens: int | None = None
    cache_read_tokens: int | None = None
    cache_write_tokens: int | None = None
    tools: list[ToolCall] = field(default_factory=list)
    #: The llm leaves' `(model, usage)` pairs, kept only until `_resolve` has priced them into
    #: `cost_usd` and the four bucket sums. Never written back out — the resolved numbers are.
    usage_leaves: list[dict] | None = None

    @property
    def cache_read_ratio(self) -> float | None:
        """`cache_read / (cache_read + input)` — §3.3's ratio, or None where nothing was reported.

        The ratio rather than the raw count because the most common silent cost regression is a
        prompt-prefix edit that stops the cache hitting, which reads here as a clean collapse from
        ~0.8 to ~0.0 and, unlike the count, does not move with traffic volume.
        """
        if self.cache_read_tokens is None or self.input_tokens is None:
            return None
        denominator = self.cache_read_tokens + self.input_tokens
        return self.cache_read_tokens / denominator if denominator else None


@dataclass
class Sample:
    """One reading of one measure, already bucketed. What the window machinery folds."""

    bucket_key: str
    call_site_id: str
    value: float
    event_at: str
    created_at: str
    trace_id: str
    project_version_id: str | None = None


UNATTRIBUTED = "__unattributed__"


def load_turns_jsonl(path: str | Path) -> list[Turn]:
    """A corpus exported by the two queries above, one JSON object per line.

    Tool spans may either ride on their turn under a `tools` array or arrive as separate lines; both
    are accepted because the natural way to run the export is two queries into two files, and the
    natural way to hand-build a fixture is one nested object. A tool row is told from a turn row by its
    carrying either `bucket_key` (already a symbol) or `kind` (raw, from `TOOL_EXPORT_SQL`).

    Anything the export could not resolve in SQL — tool symbols, priced cost — is resolved afterwards
    by one batched `_resolve` call into the shipping Java. A file that carries neither raw form needs no
    JVM, which is what keeps hand-written fixtures and `--synthetic` free of one.
    """
    turns: dict[str, Turn] = {}
    orphan_tools: list[dict] = []
    for line in Path(path).read_text().splitlines():
        if not line.strip():
            continue
        row = json.loads(line)
        if ("bucket_key" in row or "kind" in row) and "trace_id" in row and "duration_ms" in row:
            orphan_tools.append(row)
            continue
        turn = Turn(
            trace_id=row["trace_id"],
            created_at=str(row.get("created_at") or row["event_at"]),
            event_at=str(row.get("event_at") or row["created_at"]),
            call_site_id=row.get("call_site_id") or UNATTRIBUTED,
            duration_ms=_number(row.get("duration_ms")),
            cost_usd=_number(row.get("cost_usd")),
            project_version_id=row.get("project_version_id"),
            environment_id=row.get("environment_id"),
            context_id=row.get("context_id"),
            input_tokens=_int(row.get("input_tokens")),
            output_tokens=_int(row.get("output_tokens")),
            cache_read_tokens=_int(row.get("cache_read_tokens")),
            cache_write_tokens=_int(row.get("cache_write_tokens")),
            usage_leaves=_leaves(row.get("usage_leaves")),
        )
        for tool in row.get("tools") or []:
            turn.tools.append(_tool_call(tool, turn))
        turns[turn.trace_id] = turn

    for row in orphan_tools:
        turn = turns.get(row["trace_id"])
        if turn is None:
            continue  # a span whose trace was not exported: not attachable, and not a bucket of its own
        turn.tools.append(_tool_call(row, turn))

    loaded = sorted(turns.values(), key=lambda t: (t.created_at, t.trace_id))
    _resolve(loaded)
    return loaded


def _tool_call(row: dict, turn: Turn) -> ToolCall:
    """One tool span, with its symbol if the row already carries one and its raw pair if it does not."""
    return ToolCall(
        bucket_key=str(row["bucket_key"]) if row.get("bucket_key") else "",
        duration_ms=_number(row.get("duration_ms")),
        event_at=str(row.get("event_at") or turn.event_at),
        call_site_id=row.get("call_site_id") or turn.call_site_id,
        kind=row.get("kind"),
        name=row.get("name"),
    )


def _leaves(value: object) -> list[dict] | None:
    """The `usage_leaves` array as exported — a JSON array, or a JSON string holding one."""
    if value is None:
        return None
    if isinstance(value, str):
        value = json.loads(value) if value.strip() else None
    return [leaf for leaf in value if isinstance(leaf, dict)] if isinstance(value, list) else None


def _resolve(turns: list[Turn]) -> None:
    """Fill in everything only the JVM can answer, in ONE bridge call, in place.

    Two questions, one round trip, and neither is re-implemented on this side. A Python restatement of
    either would be a detector nobody ships: `ActionSymbol`'s normalization decides which spans share a
    bucket, and `TokenUsage.nonOverlapping()` + `TokenPriceBook` decide what a turn cost and whether it
    has a cost at all. Both are exactly the kind of arithmetic behaviour drift's port diverged on for a
    release, and the reason `bridge.py` exists.

    A corpus that needs neither — a hand-written fixture, a round-tripped file, `synthetic()` — never
    touches the bridge. One that does and cannot reach it raises `BridgeUnavailable`, which says what to
    run; it is never silently skipped, because a cost run that quietly abstained on everything looks
    exactly like a cost run that measured a quiet corpus.
    """
    from . import bridge  # local: keeps `corpus` importable without a JVM anywhere in sight

    symbols: list[bridge.SymbolRequest] = []
    pending_tools: list[ToolCall] = []
    for turn in turns:
        for tool in turn.tools:
            if tool.bucket_key:
                continue
            pending_tools.append(tool)
            symbols.append(bridge.SymbolRequest(id=str(len(symbols)), kind=tool.kind, name=tool.name))

    priced: list[bridge.PriceRequest] = []
    pending_turns: list[Turn] = []
    for turn in turns:
        # Column-preferred, exactly as MetricSource.cost is: a turn whose `trace.total_cost` held a
        # value is not re-derived, and only the null ones reach the price book.
        if turn.cost_usd is not None or not turn.usage_leaves:
            continue
        pending_turns.append(turn)
        priced.append(bridge.PriceRequest(id=str(len(priced)), leaves=turn.usage_leaves))

    if not symbols and not priced:
        return

    resolved = bridge.derive(symbols, priced)
    for tool, request in zip(pending_tools, symbols, strict=True):
        tool.bucket_key = resolved.symbols[request.id]
    for turn, request in zip(pending_turns, priced, strict=True):
        row = resolved.priced[request.id]
        # None where the sweep would abstain — one unpriced leaf abstains the whole turn, because a
        # partial sum understates that turn's spend by an unknown amount and puts a plausible number
        # into the distribution. Left as None rather than zeroed: unpriced is not free.
        turn.cost_usd = row["cost_usd"]
        turn.input_tokens = row["input_tokens"]
        turn.output_tokens = row["output_tokens"]
        turn.cache_read_tokens = row["cache_read_tokens"]
        turn.cache_write_tokens = row["cache_write_tokens"]


def write_turns_jsonl(turns: list[Turn], path: str | Path) -> Path:
    """Round-trip a corpus (including injected ones) so a run can be reproduced from a file."""
    out = Path(path)
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("w") as handle:
        for turn in turns:
            handle.write(
                json.dumps(
                    {
                        "trace_id": turn.trace_id,
                        "created_at": turn.created_at,
                        "event_at": turn.event_at,
                        "call_site_id": turn.call_site_id,
                        "duration_ms": turn.duration_ms,
                        "cost_usd": turn.cost_usd,
                        "project_version_id": turn.project_version_id,
                        "environment_id": turn.environment_id,
                        "context_id": turn.context_id,
                        "input_tokens": turn.input_tokens,
                        "output_tokens": turn.output_tokens,
                        "cache_read_tokens": turn.cache_read_tokens,
                        "cache_write_tokens": turn.cache_write_tokens,
                        "tools": [
                            {
                                "bucket_key": tool.bucket_key,
                                "duration_ms": tool.duration_ms,
                                "event_at": tool.event_at,
                                "call_site_id": tool.call_site_id,
                            }
                            for tool in turn.tools
                        ],
                    }
                )
                + "\n"
            )
    return out


# -------------------------------------------------------------------------------------------------
# Measures — what a window folds, per PROGRAM.md §3.1
# -------------------------------------------------------------------------------------------------

TURN_DURATION = "turn_duration"
TOOL_DURATION = "tool_duration"
COST = "cost"

#: Which sketch grid each measure lives on. Never shared: putting duration and cost on one range
#: would spend most of it on values neither measure produces.
GRID_OF = {TURN_DURATION: "duration", TOOL_DURATION: "duration", COST: "cost"}


def samples(turns: list[Turn], measure: str) -> list[Sample]:
    """Every reading of one measure, in ingest order — the order the sweep folds a page in.

    Abstention is a drop here, exactly as it is in the sweep: a turn with no duration (root span
    never ended, or never arrived) contributes nothing to the distribution rather than contributing a
    zero. `__unattributed__` is dropped at turn grain and kept at tool grain, which is not an
    inconsistency: the unattributed pile is a MIXTURE whose distribution moves whenever the mix does,
    while `tool:search_docs` is one tool however the trace that called it was tagged.
    """
    out: list[Sample] = []
    for turn in turns:
        if measure == TOOL_DURATION:
            for tool in turn.tools:
                if tool.duration_ms is None or tool.duration_ms < 0:
                    continue
                out.append(
                    Sample(
                        bucket_key=tool.bucket_key,
                        call_site_id=tool.call_site_id,
                        value=float(tool.duration_ms),
                        # The SPAN's own start: a window is a stretch of the agent's timeline, and a
                        # tool called an hour into a long trace belongs in the window that hour is in.
                        event_at=tool.event_at,
                        created_at=turn.created_at,
                        trace_id=turn.trace_id,
                        project_version_id=turn.project_version_id,
                    )
                )
            continue

        if turn.call_site_id == UNATTRIBUTED:
            continue
        value = turn.duration_ms if measure == TURN_DURATION else turn.cost_usd
        if value is None or value < 0:
            continue
        out.append(
            Sample(
                bucket_key=turn.call_site_id,
                call_site_id=turn.call_site_id,
                value=float(value),
                event_at=turn.event_at,
                created_at=turn.created_at,
                trace_id=turn.trace_id,
                project_version_id=turn.project_version_id,
            )
        )
    return out


def by_bucket(rows: list[Sample]) -> dict[str, list[Sample]]:
    """Group readings by bucket, preserving ingest order inside each."""
    grouped: dict[str, list[Sample]] = {}
    for row in rows:
        grouped.setdefault(row.bucket_key, []).append(row)
    return grouped


# -------------------------------------------------------------------------------------------------
# Synthetic traffic — for developing the harness, NEVER for setting an operating point
# -------------------------------------------------------------------------------------------------

#: Four entry points with deliberately different shapes, because the one hard problem is that
#: duration varies enormously for structural rather than temporal reasons (PROGRAM.md §1): a RAG
#: lookup and a thirty-step agent run differ by two orders of magnitude, and a detector that pooled
#: them would only ever learn that long-shaped traces are long.
#:
#: A turn's duration is BUILT from its tool calls plus the entry point's own overhead, rather than
#: drawn independently of them. That costs nothing and it is what makes the §6.1 suppression rule
#: reachable from a synthetic run at all: `research-topic` spends most of its turn inside one tool,
#: so a slowdown there genuinely accounts for the turn's, while `discover-sales-prospects` spreads
#: its time over five calls and no single one covers the turn. Both cases are real and the rule is
#: supposed to tell them apart.
#:
#: Tools are `(calls per turn, median ms, sigma)`.
SYNTHETIC_CALL_SITES = {
    "answer-faq": {"overhead_ms": 500, "sigma": 0.45, "cost": 0.004, "tools": {"retrieval:search_docs": (1, 400, 0.5)}},
    "discover-sales-prospects": {
        "overhead_ms": 1900,
        "sigma": 0.8,
        "cost": 0.11,
        "tools": {"tool:crm_lookup": (3, 700, 0.6), "tool:enrich_company": (2, 1500, 0.7)},
    },
    "summarize-thread": {"overhead_ms": 1900, "sigma": 0.5, "cost": 0.02, "tools": {"tool:fetch_thread": (1, 300, 0.4)}},
    "research-topic": {"overhead_ms": 800, "sigma": 0.5, "cost": 0.06, "tools": {"tool:deep_search": (1, 6000, 0.6)}},
}


def synthetic(
    n: int = 6000,
    *,
    seed: int = 11,
    start: str = "2026-06-01T00:00:00Z",
    seconds_between: float = 30.0,
    versions: tuple[str, ...] = ("pv_synthetic_1",),
) -> list[Turn]:
    """Lognormal traffic with an agent product's shape. Heavy-tailed by construction, because the
    tail is the whole difficulty: a mean-shift test on a heavy-tailed measure is blind to a
    regression that moves p95 and leaves the median still (PROGRAM.md §4.5).

    `versions` splits the corpus evenly across deploys, which is what `eval_deploy` needs to check
    that a finding's `since_version_id` lands on the right one.
    """
    rng = random.Random(seed)
    base = _epoch(start)
    keys = list(SYNTHETIC_CALL_SITES)
    turns: list[Turn] = []
    for i in range(n):
        site = keys[i % len(keys)]
        spec = SYNTHETIC_CALL_SITES[site]
        at = _iso(base + i * seconds_between)
        calls = [
            (bucket, call, median * math.exp(rng.gauss(0, sigma)))
            for bucket, (count, median, sigma) in spec["tools"].items()
            for call in range(count)
        ]
        # The turn encloses its tool calls, so its duration is their sum plus the entry point's own
        # overhead — never an independent draw. A generator that drew the two separately could not
        # produce the case where a tool's slowdown accounts for a turn's, and the suppression rule
        # would look untested when it was merely unreachable.
        duration = spec["overhead_ms"] * math.exp(rng.gauss(0, spec["sigma"])) + sum(ms for _, _, ms in calls)
        cache_read = int(rng.gauss(4000, 500)) if rng.random() < 0.8 else 0
        turn = Turn(
            trace_id=f"tr_{i:07d}",
            created_at=at,
            event_at=at,
            call_site_id=site,
            duration_ms=duration,
            cost_usd=spec["cost"] * math.exp(rng.gauss(0, 0.35)),
            project_version_id=versions[min(i * len(versions) // n, len(versions) - 1)],
            context_id=f"ctx_{i // 3:06d}",
            input_tokens=max(1, int(rng.gauss(1200, 250))),
            output_tokens=max(1, int(rng.gauss(400, 120))),
            cache_read_tokens=cache_read,
            cache_write_tokens=int(rng.gauss(600, 150)) if rng.random() < 0.2 else 0,
        )
        for bucket, call, ms in calls:
            turn.tools.append(
                ToolCall(
                    bucket_key=bucket,
                    duration_ms=ms,
                    event_at=_iso(base + i * seconds_between + call),
                    call_site_id=site,
                )
            )
        turns.append(turn)
    return turns


def _epoch(iso: str) -> float:
    from datetime import datetime

    return datetime.fromisoformat(iso).timestamp()


def _iso(epoch: float) -> str:
    from datetime import UTC, datetime

    return datetime.fromtimestamp(epoch, UTC).isoformat().replace("+00:00", "Z")


def _number(value: object) -> float | None:
    if value is None or value == "":
        return None
    try:
        out = float(value)  # type: ignore[arg-type]
    except (TypeError, ValueError):
        return None
    return None if math.isnan(out) else out


def _int(value: object) -> int | None:
    out = _number(value)
    return None if out is None else int(out)
