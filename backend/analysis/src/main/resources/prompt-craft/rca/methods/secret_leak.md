# Secret leak

## What it measures
Whether a call site's output contains a credential, matched by the gitleaks rule corpus. It
reads redaction's record of what it replaced first, then scans the stored output, then looks for
a bare `[REDACTED_*]` marker. One high-confidence match opens the finding, per call site and
rule; high confidence means a rule anchored on a provider's key format. There is no rate and no
comparison.

## Reading the evidence
Numbers are in each finding's `evidence.md`. `witness` rows are the spans whose output matched,
capped at 50, newest first. Each row carries the masked key (`secretKey`) and whether the stored
copy is redacted or raw (`storedAs`). `get_span` shows the output; a redacted copy shows the marker where the value was.
There are no `member` or `baseline` rows: nothing here is a rate or a comparison.

## Measurement quirks
A match is a format match. The rule does not know whether the value is live, and the span body
is where its origin shows. With more than 50 matches, the witness rows are not every match; the
count in each finding's `evidence.md` is.

## In the repo
The call site's prompt and its tools sit in its call-site file in the bundle. The code around
the request assembles its context and handles its output.
