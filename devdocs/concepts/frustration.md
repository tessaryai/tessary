# Frustration

Frustration watches whether users become frustrated with the agent, per call site, and opens a case
when a call site's rate of frustrated conversations rises above the rate it learned as its own normal.
It has two halves: a scorer that flags single user turns with a hosted decision model, and a rate test
that turns those flags into findings. Code: `backend/analysis/.../classifier/frustration/`. The rate
arithmetic is [deviation-math.md](./deviation-math.md) §2; config keys are in
[config-keys.md](../reference/config-keys.md) (`tessary.frustration.*` and the classifier blob);
tables are in [data-model.md](../reference/data-model.md) (`frustration_assessment`,
`frustration_detection`, `frustration_state`).

## Off by default

The scorer is TypeSafe's Jev, called on the org's own OpenRouter or TypeSafe key, one request per
eligible user turn. That spends the org's credit, so the built-in seeds disabled (catalog
`defaultEnabled = false`, migration `0022` for rows that existed before) and a person turns it on
through the enable modal, which takes the key and sets the `frustration` lane. Enabling without a key
the lane can run on is refused (`PROVIDER_REQUIRED`). A key the provider refuses, or no key at all,
pauses the classifier (`provider_rejected`, `no_provider`); a paused sweep sends nothing and skips what
it passes, and saving the key or pressing Retry lifts the pause.

It is not a per-observation LLM call in the catalog's cost sense: it is a hosted classifier call, and
only a filtered subset of turns reaches it.

## Which turns are sent

A turn is sent only when the four messages before it are user, assistant, user, assistant, each with
text, and the current user message has text (`FrustrationTurnBuilder`). A conversation's first two user
turns are therefore never sent, and an assistant turn that ended on a tool call with no text after it
makes the turn ineligible. An ineligible turn is not sent and leaves no row. Spans are redacted before
they are stored, so what is sent is already redacted.

The state is the current message and the four before it, with per-message caps, pasted blocks
replaced by a `[PASTE: n lines, k chars]` marker, and long messages cut to their head and tail.

A turn is flagged when `P(unhappy_with_assistant)` exceeds `threshold` (0.40, a starting value).
`unhappy_other_cause`, frustration about something outside the chat, never flags. The question text,
its shape and the threshold hash into `scorer_version`, so changing any of them starts a new set of
assessment rows rather than mixing scales.

## One flag per conversation

A conversation is keyed `COALESCE(thread_id, session_id)`. Its first flagged turn writes a
`frustration_detection` row, and while that row stands uncleared the sweep sends none of the
conversation's later turns. Every sent turn, flagged or not, is a `frustration_assessment` row with the
exact request and response bodies.

## The conversation is the trial

The rate test is Tool Error's Bernoulli CUSUM, run per call site with a conversation as the trial
instead of a tool call. A conversation belongs to the call site of its first scored turn, and stays
there: a flag on a later turn at another call site counts against the first call site, so the
numerator and denominator are the same population. The finding cites the flagged turn itself, so RCA
reads the turn where it happened.

A conversation is a failure while it holds an uncleared flag. The replay rebuilds from the tables on
every pass, so a conversation flagged on a later turn becomes a failure in its original hour, and one
cleared by a `false_alarm` resolve stops being one.

## The learned rate, and what it cannot see

Each call site learns its reference from its first 200 scored conversations, then freezes it. It is
compared with its own past, not with a shipped number. The consequence is plain: a call site that
frustrates users from its first day learns that as its normal and is flagged only if it gets worse.
A call site too quiet to reach 200 conversations inside the 28-day replay window is never judged, and
its Tuning row says `learning n/200`.

## What a case says, and what a resolve does

An alarming call site files one `frustration_rate` finding per spell, ruled positive at filing with no
triage, and opens or joins its case. Its evidence is pairs of witness rows: the frustrated conversation
at session grain and its flagged turn at trace grain, newest first, capped at 50. RCA on the case writes
a cause report instead of a metric-movement report: causes grouped by what the agent did, each citing
the conversations that show it.

Resolving the case, either way, zeroes the accumulator and drops the reference, so the call site
learns its rate again from the traffic after the resolve (Tool Error keeps its reference on reset; this
one does not, on purpose). `fixed` does only that. `false_alarm` also clears the flag on every
conversation cited by any finding in the case, so they stop counting as failures and their later turns
are sent again. The cost of re-learning: a case resolved as `fixed` before the fix lands teaches the
degraded rate as the new normal.
