# Frustration

## What it measures
The share of one call site's sessions in which the user became frustrated with the agent,
against the rate the call site learned as its own normal. A session is one conversation on one
call site and one trial; it fails while it holds an uncleared flag. A hosted decision model
scores each eligible user turn. A turn is eligible only when the four messages before it are
user, assistant, user, assistant, each with text, so a session's first two user turns are never
scored. A turn is flagged when its unhappy-with-assistant score passes the threshold.
Frustration aimed at something outside the chat never flags. A session stops being scored at
its first flag.

## Reading the evidence
Numbers are in each finding's `evidence.md`. `witness` session rows are every frustrated session
since the onset. `witness` trace rows are the user turn that was flagged inside each of those sessions. `member` session rows are
every session scored on the call site since the onset. There are no `baseline` rows: the normal
rate is a number, not a set of rows.

## Measurement quirks
The flagged turn marks where the user reacted, not the turn that prompted the reaction. The
learned rate absorbs a call site that was frustrating from the start; the finding is about a
rise. The rate may still be learning when judging starts. A resolve restarts the accumulator and
re-learns the rate from the traffic after it.

## In the repo
The call site's prompt and configuration sit in its call-site file in the bundle. The code
around the request builds the conversation the model sees.
