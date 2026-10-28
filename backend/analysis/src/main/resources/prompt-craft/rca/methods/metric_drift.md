# Duration drift and cost drift

## What it measures
A distribution of per-turn or per-tool durations, or costs, over one closed window, against a
reference. One finding is one closed window. Duration is measured on the root step of a run;
cost and tokens on a whole-run row are the trace's rollup, and `models` lists every model the
run called. The reference is a pinned window, or a rolling control over the previous three
weeks' days, newer weighted more, confirmed regressions excluded. Each finding's `evidence.md`
says which. The onset is the change point inside the window.

## Reading the evidence
Numbers are in each finding's `evidence.md`. Its `explains` list is the sibling buckets this drift
accounts for and so suppressed; `covered` is the share of that bucket's own shift this drift
covers, and above 1 is ordinary. `member` rows are every sample folded into the window, in fold order: span
grain for a tool measure, whole-run rows for a turn measure. `baseline` rows are the comparison
side: the pinned window's rows, or a sample spread over a rolling reference's days, plus the
rows just before the change point. A pinned reference a person moved has only those last rows.
`changepoint` rows are the first rows from the change point on. A tool bucket spans every call
site that calls the tool, and each row carries its own `callSiteId`. There are no `witness`
rows: every member is part of the shifted population, and none is marked as the failure.

## Measurement quirks
The quantiles come from a log histogram with 5% bins, so quantiles you take from the rows will
not match them to the digit. The rows were written once, when the finding opened; a persisting
regression re-fires on later windows and appends nothing.

## In the repo
The call site's prompt and configuration sit in its call-site file in the bundle and in the code
that builds and sends the request.
