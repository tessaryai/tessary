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

## One flag per session at a time

A session is a conversation on one call site. The conversation is the trace's `session_id`.
`thread_id` is only a column and never groups turns: a producer that sends one thread id per user for
all time still has one conversation per session, and one that wants a whole user's history read as one
conversation sends that id as the session id. A trace with no session is in no conversation and is not
scored. The call site is the scored span's, written into the detection's `evidence.call_site_id`
and the assessment's `call_site_id`. A session's first flagged turn writes a `frustration_detection`
row, and while that row stands uncleared the sweep sends none of the session's later turns, so a
flagged session has no later trials until its flag is cleared. A flag on one call site does not stop
another call site in the same conversation. Every sent turn, flagged or not, is a
`frustration_assessment` row with the exact request and response bodies.

The trace and session views read the same uncleared rows (`storage/TraceDetectionRepository`): the
Detected by filter and column on the traces list, and the marker on the flagged message in the detail
views. A turn whose flag a `false_alarm` resolve cleared is not marked.

## The conversation-hour is the trial

The rate test is Tool Error's Bernoulli CUSUM, run per call site with a conversation-hour as the trial
instead of a tool call. In each UTC hour, a conversation with at least one scored turn on the call site
counts once, and it fails when a turn in that hour was flagged and its `frustration_detection` stands
uncleared. A long conversation counts once in each hour it is active. A conversation that reaches two
call sites is a trial on each, and a flag on one never counts against the other, so the numerator and
denominator are the same population.

A worked example, with a deploy at 10:00. A has turns at 09:10, 09:40 and 10:20 (flagged); B at 09:30
and 09:50; C at 10:05, 10:15, 10:25 (flagged) and 10:35 (flagged); D at 10:50. Hour 09:00 is A and B,
0 of 2. Hour 10:00 is A, C and D, 2 of 3. C's 10:35 flag can exist only when both turns were scored in
one pass, since the sweep stops at a conversation's first flag.

The hour is the unit for two reasons. A deploy part way through a conversation shows in the hour it
happened: bucketed at its first scored turn, A would count only at 09:00 and fail there for a flag
raised after the deploy. And an hour's count is final once its turns are scored, so it can be learned
soon after it closes. The flag test is per turn: a later turn's flag never fails an earlier hour of
the same conversation. A `false_alarm` clear un-fails the hour, and the replay rebuilds from the tables
on every pass, so the clear reaches old hours.

## The learned rate, and what it cannot see

Each call site starts being judged once its reference holds `min_baseline_conversations` (100), and
the reference keeps learning each later hour until it holds `freeze_baseline_conversations` (1,000),
then stops moving. A blob without `freeze_baseline_conversations` freezes the reference the moment
judging starts. All three counts are conversation-hours. An hour is learned one hour after it ends on
the call site's own clock (`reference_settle_hours`, 1), which covers the scorer's lag; a
conversation's later flag lands in its own hour, never an earlier one. The reference is its own past,
not a shipped number. The consequence is plain: a call site that frustrates users from its first day
learns that as its normal and is flagged only if it gets worse. A call site too quiet to reach 100
conversation-hours inside the 28-day replay window is never judged, and its Tuning row says
`learning n/100`.

Until `0053` the trial was a conversation bucketed in the hour of its first scored turn, learned 24
hours after it ended. `0053` cleared every `frustration_state` reference, watermark, accumulator and
`learned_through`, and fenced re-learning at an open finding's onset; the state epoch's trial tag
(`frustration-hour`) makes the first sweep take the new epoch without cutting a new reset fence.

## What a case says, and what a resolve does

An alarming call site files one `frustration_rate` finding per spell, ruled positive at filing with no
triage, and opens or joins its case. Its evidence is the rate's two sides, enumerated the way Tool Error
enumerates its calls, one turn row per conversation-hour since onset: the first flagged turn of a
frustrated conversation-hour as `witness` and `member`, and the last scored turn of a calm one as
`member`. Each row names its session as well as its trace (a grain `0053` added to
`finding_evidence_grain_check`), so the `false_alarm` clear, the retention pin and RCA's session
citations read the session off the turn row; there are no session rows. In the example, hour 10:00
cites A at 10:20 and C at 10:25 as witnesses and D at 10:50 as a calm member. Neither role is capped,
and both are written on every pass up to the spell's last hour. When a later turn of an hour is scored, or an
earlier one is flagged, the turn that stands for that conversation-hour moves, and the next pass takes out the
rows of the turn it replaced, so each conversation-hour keeps one row per role. The finding and case pages read the
witnesses 50 at a time (`GET /findings/{id}/frustrated-sessions`). RCA on the case reads every witness
turn and writes a cause report instead of a metric-movement report: causes grouped by what the agent
did, each citing the turns or sessions that show it.

Resolving the case, either way, zeroes the accumulator and drops the reference, so the call site
learns its rate again from the traffic after the resolve (Tool Error keeps its reference on reset; this
one does not, on purpose). `fixed` does only that. `false_alarm` also clears the flag on every
session a witness row of any finding in the case names, on that finding's call site only, so they stop counting as
failures and their later turns are sent again. The cost of re-learning: a case resolved as `fixed` before the fix lands teaches the
degraded rate as the new normal.
