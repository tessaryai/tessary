# Frustration

Frustration watches whether users become frustrated with the agent, per call site, and opens a case
when a call site's rate of frustrated sessions rises above the rate it learned as its own normal.
It has two halves: a scorer that flags single user turns with a hosted decision model, and a rate test
that turns those flags into findings. Code: `backend/analysis/.../classifier/frustration/`. The rate
arithmetic is [deviation-math.md](./deviation-math.md) §2; config keys are in
[config-keys.md](../reference/config-keys.md) (`tessary.frustration.*` and the classifier blob);
tables are in [data-model.md](../reference/data-model.md) (`frustration_assessment`, `frustration_detection`,
`frustration_state`).

## Off by default

The scorer is TypeSafe's Jev, called on the org's own OpenRouter or TypeSafe key, one request per
eligible user turn. That spends the org's credit, so the built-in seeds disabled (catalog
`defaultEnabled = false`, migration `0022` for rows that existed before) and a person turns it on
through the enable modal, which takes the key and sets the `frustration` lane. Enabling without a key
the lane can run on is refused (`PROVIDER_REQUIRED`); credit is not checked at enable. A key the
provider refuses, a key with no funds left, or no key at all pauses the classifier (`provider_rejected`,
`no_credit`, `no_provider`); a paused sweep sends nothing and skips what it passes, and saving the key or
pressing Retry lifts the pause.

A build that supplies `PLATFORM` (see [provider-keys.md](../guides/provider-keys.md)) can also run the
lane on its own key, last in the lane's order, so an org's own key always wins. Those calls are booked
platform-funded. An org with no credit left for it pauses as `no_credit`, the same pause as its own key
running dry, and a refusal of the deployment's key, or that key running dry, pauses as
`platform_unavailable`, which also logs an error for the operator. This build supplies no `PLATFORM`,
so `platform_unavailable` never happens here.

It is not a per-observation LLM call in the catalog's cost sense: it is a hosted classifier call, and
only a filtered subset of turns reaches it.

## Which call sites are scored

One user turn can make several model calls: a router that picks a lane, the reply, a memory pass that
updates notes about the user. Each is its own call site, and only the reply is a conversation the user
reads. The classifier takes the same call-site list as every other one (`classifier.call_site_ids`, set
with `PUT /classifiers/{id}/call-sites` or the picker in the classifier rail): null, the default, scores
every call site, and a list scores only those. The worker applies the list before the detector sees a
turn. A change applies to spans the sweep reads after it; the cursor is not rewound, since that would
spend the org's credit on history. Until `0032` the list was its own table, `frustration_scope`, where
no picks meant nothing was scored; `0032` moved the picks into `call_site_ids` and dropped the table.

In each top-level trace, the span scored for a call site is its first `llm` or `agent` span by
`(started_at, id)`: the call that received the user's message, before any tool round of the same call
site. The four messages before it come from the two earlier turns of the conversation that reached the
same call site; a turn that only ran a router or a memory pass is not one of them. A turn's user
messages are those after its input's last assistant message, so a call site that sends the whole chat
on every call gives each turn once, and a context block it sends under the user role is not read as
the user's words.

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
its shape, the threshold and the conversation key hash into `scorer_version`, so changing any of them
starts a new set of assessment rows rather than mixing scales.

## One flag per session

A session is a conversation on one call site. The conversation is the trace's `session_id`.
`thread_id` is only a column and never groups turns: a producer that sends one thread id per user for
all time still has one conversation per session, and one that wants a whole user's history read as one
conversation sends that id as the session id. A trace with no session is in no conversation and is not
scored. The call site is the scored span's, written into the detection's `evidence.call_site_id`
and the assessment's `call_site_id`. A session's first flagged turn writes a `frustration_detection`
row, and while that row stands uncleared the sweep sends none of the session's later turns. A flag on
one call site does not stop another call site in the same conversation. Every sent turn, flagged
or not, is a `frustration_assessment` row with the exact request and response bodies.

The trace and session views read the same uncleared rows (`storage/TraceDetectionRepository`): the
Detected by filter and column on the traces list, and the marker on the flagged message in the detail
views. A turn whose flag a `false_alarm` resolve cleared is not marked.

## The session is the trial

The rate test is Tool Error's Bernoulli CUSUM, run per call site with a session as the trial instead
of a tool call. A conversation that reaches two call sites is a trial on each, and a flag on one
never counts against the other, so the numerator and denominator are the same population. The finding
cites the flagged turn itself, so RCA reads the turn where it happened.

A session is a failure while it holds an uncleared flag. The replay rebuilds from the tables on every
pass, so a session flagged on a later turn becomes a failure in the hour of its first scored turn, and
one cleared by a `false_alarm` resolve stops being one.

## The learned rate, and what it cannot see

Each call site starts being judged once its reference holds `min_baseline_conversations` (100), and
the reference keeps learning each later hour until it holds `freeze_baseline_conversations` (1,000),
then stops moving. A blob without `freeze_baseline_conversations` freezes the reference the moment
judging starts. The reference is its own past, not a shipped number. The consequence is plain: a call
site that frustrates users from its first day learns that as its normal and is flagged only if it gets
worse. A call site too quiet to reach 100 sessions inside the 28-day replay window is never
judged, and its Tuning row says `learning n/100`.

## What a case says, and what a resolve does

An alarming call site files one `frustration_rate` finding per spell, ruled positive at filing with no
triage, and opens or joins its case. Its evidence is the rate's two sides, enumerated the way Tool Error
enumerates its calls: every session scored on the call site since onset as `member`, and every frustrated
one as `witness`, a session row followed by a trace row for the turn that fired. Neither is capped, and both
are written on every pass up to the spell's last hour. The finding and case pages read the witnesses 50 at
a time (`GET /findings/{id}/frustrated-sessions`). RCA on the case reads every witness session and writes a
cause report instead of a metric-movement report: causes grouped by what the agent did, each citing the
sessions that show it.

Resolving the case, either way, zeroes the accumulator and drops the reference, so the call site
learns its rate again from the traffic after the resolve (Tool Error keeps its reference on reset; this
one does not, on purpose). `fixed` does only that. `false_alarm` also clears the flag on every
session cited by any finding in the case, on that finding's call site only, so they stop counting as
failures and their later turns are sent again. The cost of re-learning: a case resolved as `fixed` before the fix lands teaches the
degraded rate as the new normal.
