# Find the cause

This finding is confirmed: the behaviour it describes is real. Find out why it happens. The
engineer who reads your report will act on it.

A cause is any reason the finding happens. It is one of two kinds: a change over time (a commit,
a prompt, a model, a tool, an upstream service, the traffic) or a standing behaviour of the
agent, its code, its prompt, its tools or its data.

## This run

- finding id: `{finding_id}`
- onset: {onset}; last seen: {last_seen}
- flagged: {flagged_count} {grain}
- time budget: {time_budget_minutes} minutes

{repo}

{baseline}

## Read first

- `dossier/finding.md`: the claim, what it is over, and since when.
- `dossier/evidence.json`: the classifier's own numbers, verbatim.
{method_line}
- `dossier/tools.md`: the MCP tools that reach the rows, traces, spans and sessions.

## Investigate

Consider every plausible cause of both kinds, and do not settle on the first one that fits. Try
to disprove each candidate before you keep it.

A cause has two halves: a mechanism you can point at, and its effect in the flagged rows you
read. Every claim about a row rests on a row you opened.

Compare only against `baseline` rows that are in this finding's evidence; `member` rows are not
a comparison side. Do not fetch normal traffic to build a comparison.

Other open findings and cases on this project may show what else is moving. Never look up this
finding or the case that owns it.

Causes may overlap: one flagged row can belong to several.

## Grade

Grade each cause on its own against the schema's definitions of `high` and `medium`. Below
`medium` is not a cause; it is ruled out. Several causes may be `high`. When no cause reaches
`medium`, the verdict is `no_cause_found`, with what you ruled out. That is a full answer.

## Report

Cite only trace and session ids from this finding's evidence that you read; any other id is
discarded. Every field except `detailed_report` is for a reader with no context: plain words,
no ids, queries or statistics.

When the time budget runs short, return the causes you have at the confidence they earned, and
say what you did not check.

Return only the JSON object the schema requires.
