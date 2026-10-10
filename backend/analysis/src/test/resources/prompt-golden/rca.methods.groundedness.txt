# Groundedness

## What it measures
The share of one call site's traces with an answer the model flagged, against the rate the call
site learned as its own normal. Only call sites whose shape is `rag_answer`, `summarize` or
`extract` are scored. A token classifier reads the retrieved documents and the whole answer in
one pass and scores each sentence for the probability that it is unsupported: contradicted by
the documents, or stated where they say nothing. An answer is flagged when its strongest
sentence scores at or above the flag threshold. Answers with no retrieved documents, or with
nothing checkable in them, are not scored and are not trials. A trace is one trial on each call
site it had an answer scored on, and it fails while one of those answers holds an uncleared flag.

## Reading the evidence
Numbers are in each finding's `evidence.md`. `witness` trace rows are every trace with a flagged answer since the onset. `witness` span rows are each flagged answer;
on `get_finding_evidence` each carries `flaggedSentences`, the sentences the model marked, with
their scores. The question, the answer and the retrieved documents are in the span body, from
`get_span`. `member` trace rows are every trace scored on the call site since the onset. There
are no `baseline` rows: the normal rate is a number, not a set of rows.

## Measurement quirks
About one flagged sentence in three is supported after all. A flag says the model saw no support
in the documents, not that the sentence is wrong, and the model's false-alarm rate depends on
the domain. A true fact
taken from a tool call but absent from the retrieved documents counts as unsupported. The
learned rate absorbs a call site that answered badly from the start; only a rise is reported.
Two flagged answers in one trace are one failure.

## In the repo
The call site's shape, prompt and configuration sit in its call-site file in the bundle. The
documents the answer is checked against enter the request from the code around it.
