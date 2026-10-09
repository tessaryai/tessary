# Duration drift and cost drift

## What it measures
A distribution of per-turn or per-tool durations, or costs, over one closed window, against a
reference. One finding is one closed window. Duration is measured on the root step of a run;
cost and tokens on a whole-run row are the trace's rollup, and `models` lists every model the
run called. The reference is one of two: a pinned window, or a rolling control merged from the
daily windows of the previous three weeks, newer days weighted more, with the days of a
confirmed regression excluded. `dossier/evidence.json` says which.

## Reading the evidence
Numbers are in `dossier/evidence.json`. Its `explains` list is the sibling buckets this drift
accounts for and so suppressed; `covered` is the share of that bucket's own shift this drift
covers, and above 1 is ordinary. `member` rows are every sample folded into the window, in fold order: span
grain for a tool measure, whole-run rows for a turn measure. `baseline` rows are the pinned
window's rows. A rolling reference has no rows behind it; the evidence describes the days it
used. A pinned reference that a person moved has no rows behind it either. A tool bucket spans
every call site that calls the tool, and each row carries its own `callSiteId`. There are no
`witness` rows: every member is part of the shifted population, and none is marked as the
failure.

## Measurement quirks
The quantiles come from a log histogram with 5% bins, so quantiles you take from the rows will
not match them to the digit. The member rows were written once, when the finding opened; a
regression that persists re-fires on later windows, and those rows are not appended.

## In the repo
The call site's prompt and configuration sit in its call-site file in the bundle and in the code
that builds and sends the request. The models and tools the call site uses are set where that
request is built.
