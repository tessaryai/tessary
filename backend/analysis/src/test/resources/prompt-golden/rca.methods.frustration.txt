# Frustration

## What it measures
The share of one call site's sessions in which the user became frustrated with the agent,
against the rate the call site learned as its own normal. A trial is a session in one UTC hour
it has a scored turn; it fails when a turn in that hour was flagged and the flag stands. A
hosted decision model scores each eligible user turn: one whose four messages before it are
user, assistant, user, assistant, each with text, so a session's first two user turns are never
scored. A turn is flagged when its unhappy-with-assistant score passes the threshold.
Frustration aimed at something outside the chat never flags. A session stops being scored at
its first flag, so it has no later trials until the flag is cleared.

## Reading the evidence
Numbers are in each finding's `evidence.md`. Each row is a turn, naming its session, for one
session-hour since the onset; all are `member` rows. A calm session-hour's is its last scored
turn, the comparison side. A frustrated one's is its first flagged turn, also a `witness`;
exported, it carries the four messages before it. There are no `baseline` rows.

## Measurement quirks
The flagged turn marks where the user reacted, not the turn that prompted the reaction. The
learned rate absorbs a call site that was frustrating from the start; the finding is about a
rise. A resolve restarts the accumulator and re-learns the rate from the traffic after it.

## In the repo
The call site's prompt and configuration sit in its call-site file in the bundle. The code
around the request builds the conversation the model sees.
