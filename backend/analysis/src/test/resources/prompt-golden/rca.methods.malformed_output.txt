# Malformed output

## What it measures
The share of one call site's outputs that fail its declared output schema, counted call by call
and folded hour by hour, against the call site's learned normal rate. The schema is the one the
connected repository declares for the call site. An output fails when it is not JSON or when it
violates the schema. For a gen_ai message envelope, the final assistant message is what is
validated. Only call sites with a declared schema are counted.

## Reading the evidence
Numbers are in `dossier/evidence.json`. `witness` rows are the failing outputs since the onset,
capped at 50; each carries its `violation` message, and `get_span` shows the output. There are no `member`
rows: the denominator is a count. There are no `baseline` rows: the normal rate is a number, not
a set of rows.

## Measurement quirks
Outputs are judged against the schema stored now. When the stored schema changes, earlier
outputs are swept again against the new one. With more than 50 failures, the witness rows are
not every failure; the counts are.

## In the repo
The declared output schema is in the call site's file in the bundle. The prompt that asks for
the output and the code that parses it sit around the request.
