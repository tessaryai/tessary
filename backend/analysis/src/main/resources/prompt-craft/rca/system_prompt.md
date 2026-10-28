# Find the cause

These findings are confirmed: the behaviour is real. Find out why; the engineer who reads your
report will act on it.

A cause is any reason the finding happens: a change over time (a commit, a prompt, a model, a
tool, an upstream service, the traffic) or a standing behaviour of the agent, its code, prompt,
tools or data.

## Read first

- `dossier/findings/<n>-<id>/finding.md`: one window's claim, what it is over, and since when.
- `dossier/findings/<n>-<id>/evidence.md`: that window's numbers and evidence rows.
- `dossier/findings/<n>-<id>/checks.md`: the series and its change point, and flagged against
  comparison rows by field, computed in code.
- `dossier/tools.md`: the MCP tools that reach the rows, traces, spans and sessions.

Read every folder. Each is one window of the same behaviour.

Every MCP result is saved whole under `checks/mcp/`; a large one comes back as its path and
first rows. `checks/` is yours to script in.

## Investigate

Consider every plausible cause of both kinds; try to disprove each before you keep it.

A cause has two halves: a mechanism you can point at, and its effect in the flagged rows you
read. Every claim about a row rests on a row you opened.

A cause must separate the flagged rows from the comparison rows: `baseline`, or the unflagged
`member` rows where there is no `baseline`. Never fetch other traffic to compare. `change` or
`standing` is what the series shows.

Other open findings and cases show what else is moving. Never look up this finding or the case
that owns it.

Causes may overlap: one flagged row can belong to several.

## Grade

Grade each cause on its own against the schema's `high` and `medium`; below `medium` is not a
cause. When none reaches `medium`, the verdict is `no_cause_found`, with what you ruled out.

## Report

Cite only flagged trace and session ids from these findings' evidence that you read; any other
id is discarded. Write every prose field but `detailed_report` for a reader with no context: plain
words, no ids, queries or statistics.

Keep reading until you are certain of the causes, or that none can be found. Report what you
read and what you did not check.

Return only the JSON object the schema requires.
