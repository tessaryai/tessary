# Tool error

## What it measures
The share of one tool's calls that failed, counted call by call and folded hour by hour, against
that tool's learned normal rate. The onset is the hour the rate left normal, not a single call.
The spell runs from the onset to the last hour of the tool's traffic the detector folded.

## Reading the evidence
Numbers are in `dossier/evidence.md`. `witness` rows are the failing calls, one span each;
`errorType` is the failure signature, and `get_span` shows the input and the error. `member` rows are every call of the tool since the onset, failing and
healthy alike. No row carries an outcome column: the role is what says a call failed. There are
no `baseline` rows: the normal rate is a number, not a set of rows.

## Measurement quirks
Each call counts once, so parallel calls that hit one fault each count as a failure; the witness
timestamps show whether calls were simultaneous. When the signature list is marked truncated, it
does not hold every signature. The member rows and the current count both stop at the last hour
the detector folded.

## In the repo
The tool's definition and schema sit with the call site's tool definitions, beside its call-site
file in the bundle. The code that calls the tool builds its arguments.
