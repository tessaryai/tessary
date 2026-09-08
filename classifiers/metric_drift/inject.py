# SPDX-License-Identifier: Apache-2.0
"""The injection operators — PROGRAM.md §12.1, PLAN.md §9.2.

There is no gold set for "this distribution moved for a bad reason", so recall is measured against
programmatic regressions applied to real traffic: take a corpus, break one bucket in a way production
actually breaks, and ask whether the detector notices and how late.

Three operators, chosen because each fails differently:

- `scale_duration` — everything in one bucket got slower by a constant factor. The easy case, and the
  one the floor is expressed in: a 1.2× injection at a floor of ln(1.2) is the boundary by
  construction, so the detection rates at 1.2 / 1.5 / 2.0 read directly as "does the floor do what it
  says".
- `collapse_cache` — a prompt-prefix edit stops the cache hitting. The most common silent cost
  regression there is, and it moves no duration at all.
- `retry_loop` — the agent makes more calls, each exactly as fast as it always was. This is the case
  turn duration exists for and the case §6.1's suppression rule must not swallow: every tool bucket
  stays flat, so nothing at tool grain can explain the turn.

Every operator applies from an ONSET part-way through the corpus rather than to the whole of it. A
regression that was always there is not a regression — it is the baseline, and a detector comparing a
bucket against its own past would be right to stay silent.
"""

from __future__ import annotations

import copy
from dataclasses import dataclass

from .corpus import Turn

#: How much more an output token costs than an input token, used only by `collapse_cache` to decide
#: how much of a turn's dollars were input-side. A modelling assumption of the INJECTOR — nothing in
#: the detector believes it — and roughly the ratio across the frontier families. It is printed in
#: the eval report so a reader can discount the cost deltas accordingly.
OUTPUT_PRICE_RATIO = 5.0

#: What a cache read costs relative to a fresh input token. 0.1 is Anthropic's rate; OpenAI's
#: automatic caching is 0.5 and Gemini's differs again. Same status: an assumption of the injector.
CACHE_READ_DISCOUNT = 0.1


@dataclass
class Injection:
    """A corpus with one bucket broken, and everything the report needs to say what was done."""

    turns: list[Turn]
    operator: str
    bucket_key: str
    detail: str
    #: Event time from which the regression applies. Findings before it are false positives on this
    #: run just as they are on the null one; the lag is counted in windows after it.
    onset_at: str


def _split_at(turns: list[Turn], predicate, onset_fraction: float) -> tuple[list[Turn], str]:
    """Deep-copy the corpus and return it with the event time the injection starts at."""
    affected = [t for t in turns if predicate(t)]
    if not affected:
        raise ValueError("no turn matches the injection target")
    onset_index = int(len(affected) * onset_fraction)
    onset_at = affected[min(onset_index, len(affected) - 1)].event_at
    return copy.deepcopy(turns), onset_at


def scale_duration(turns: list[Turn], call_site_id: str, factor: float, onset_fraction: float = 0.5) -> Injection:
    """Multiply one call site's turn durations, and the tool calls inside them, by `factor`.

    The tool calls move too because that is what a real slowdown looks like — a slower dependency, a
    smaller instance, a region change — and because it is what makes the suppression rule's job
    non-trivial: here the tool grain genuinely does explain the turn, so the tool finding SHOULD be
    the one that survives.
    """
    out, onset_at = _split_at(turns, lambda t: t.call_site_id == call_site_id, onset_fraction)
    touched = 0
    for turn in out:
        if turn.call_site_id != call_site_id or turn.event_at < onset_at:
            continue
        if turn.duration_ms is not None:
            turn.duration_ms *= factor
        for tool in turn.tools:
            if tool.duration_ms is not None:
                tool.duration_ms *= factor
        touched += 1
    return Injection(out, "scale_duration", call_site_id, f"x{factor} on {touched} turns", onset_at)


def collapse_cache(turns: list[Turn], call_site_id: str, onset_fraction: float = 0.5) -> Injection:
    """Turn one call site's cache reads into full-price input tokens, and reprice the turn.

    The ratio `cache_read / (cache_read + input)` collapses from whatever it was to zero, which is
    exactly what a prompt-prefix edit does. Cost is re-derived from the token counts under the two
    assumptions at the top of this module rather than repriced through `TokenPriceBook`: the harness
    has no price book, and inventing one would make the dollar figures look more authoritative than
    they are. The RATIO is exact; the dollars are modelled.
    """
    out, onset_at = _split_at(turns, lambda t: t.call_site_id == call_site_id, onset_fraction)
    touched = 0
    for turn in out:
        if turn.call_site_id != call_site_id or turn.event_at < onset_at:
            continue
        cache_read = turn.cache_read_tokens or 0
        if not cache_read or turn.input_tokens is None:
            continue
        before = turn.input_tokens + CACHE_READ_DISCOUNT * cache_read + OUTPUT_PRICE_RATIO * (turn.output_tokens or 0)
        after = turn.input_tokens + cache_read + OUTPUT_PRICE_RATIO * (turn.output_tokens or 0)
        if turn.cost_usd is not None and before > 0:
            turn.cost_usd *= after / before
        turn.input_tokens += cache_read
        turn.cache_read_tokens = 0
        touched += 1
    return Injection(out, "collapse_cache", call_site_id, f"cache read -> 0 on {touched} turns", onset_at)


def retry_loop(turns: list[Turn], call_site_id: str, extra_calls: int = 2, onset_fraction: float = 0.5) -> Injection:
    """Make the agent repeat its tool calls, each one exactly as fast as before.

    *Eleven tool calls where three used to do.* The turn gets longer by the repeated calls' own time;
    every tool bucket's DISTRIBUTION is untouched, because the added samples are drawn from the same
    calls the bucket already contained. So a tool-grain detector is blind to this by construction, and
    the turn finding must survive suppression — PLAN.md §11 names the opposite outcome (a real turn
    regression swallowed by a coincident tool shift) as the risk this operator exists to check.
    """
    out, onset_at = _split_at(turns, lambda t: t.call_site_id == call_site_id, onset_fraction)
    touched = 0
    for turn in out:
        if turn.call_site_id != call_site_id or turn.event_at < onset_at or not turn.tools:
            continue
        repeats = []
        for _ in range(extra_calls):
            for tool in turn.tools:
                clone = copy.deepcopy(tool)
                repeats.append(clone)
                if clone.duration_ms is not None and turn.duration_ms is not None:
                    turn.duration_ms += clone.duration_ms
        turn.tools.extend(repeats)
        touched += 1
    return Injection(
        out, "retry_loop", call_site_id, f"+{extra_calls}x tool calls on {touched} turns", onset_at
    )
