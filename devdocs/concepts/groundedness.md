# Groundedness

Groundedness watches whether an agent's answers state things their source does not support, per call
site, and opens a finding when a call site's share of traces with a flagged answer rises above the
share it learned as its own normal. It has two halves: a scorer that flags answers with a model the
self-hoster runs on their own GPU, and a rate test that turns those flags into findings. Code:
`backend/analysis/.../classifier/detector/groundedness/`. The rate arithmetic is
[deviation-math.md](./deviation-math.md) §2; config keys are in
[config-keys.md](../reference/config-keys.md) (`tessary.groundedness.*`, `tessary.observer.encoder.*`,
and the classifier blob); tables are in [data-model.md](../reference/data-model.md)
(`groundedness_assessment`, `groundedness_detection`, `groundedness_state`); the model's measured
quality is [classifier-quality.md](../reference/classifier-quality.md).

## The model runs outside Tessary

The scorer is `tessaryai/groundedness-classifier-v1` (MIT, ModernBERT-large with a token head), served
by [`classifiers/groundedness/serve.py`](../../classifiers/groundedness/serve.py) on a GPU: a Mac with
Apple silicon, or a GPU instance on AWS. It is not a classify-service head, and it is not an LLM call:
there is no provider key and no per-request bill. The backend reaches it at
`tessary.observer.encoder.url` with `POST /classify` (see the server's README for the contract).

The built-in seeds disabled (catalog `defaultEnabled = false`), because a person has to set up the
model first. The enable modal hands them a prompt for their coding agent, which follows the setup file
for the chosen mode (`classifiers/groundedness/setup/`), linked at the running version's git ref.

## What it scores, and against what

`GroundednessInputs` decides, in this order:

1. The call site's shape must be `rag_answer`, `extract`, or `summarize`. Other shapes are open
   generation with no source to be ungrounded from, and a call site with no shape is skipped.
2. The answer must be non-blank and assert something checkable (`VerifiableClaims`). Greetings and
   questions never reach the model.
3. When the conversation retrieved documents (`retrieval.documents.*`), the passages are those
   documents, best rank first, and the user's text is the question. The documents come from this
   trace when it retrieved any, else from the nearest earlier trace in the conversation that did, so
   a follow-up answered from an earlier retrieval is still scored against it.
4. When the conversation retrieved nothing, the passage is the prompt (system and user text) with no question. The
   exception is a `rag_answer` turn whose conversation reached outside (any tool, MCP, retrieval, or
   reranker span) and captured nothing readable: its source is not in the trace, so it is
   abstained. `extract` and `summarize` keep the prompt, because for those shapes the prompt is the document.

The model reads the passages and the answer in one pass of up to 8,192 tokens. `serve.py` first clips
each passage to 20,000 characters and the answer to 8,000 (`GROUNDEDNESS_MAX_PASSAGE_CHARS`,
`GROUNDEDNESS_MAX_ANSWER_CHARS`), then truncates only the passages and question to fit the window. An
answer too long to leave room for them is refused, and a refused answer is left unscored rather than
counted clean. The model gives each sentence a P(unsupported). An
answer is flagged when its strongest sentence reaches `threshold` (0.975, the model's 2% false-alarm
point on RAGTruth test). The model revision, the input layout and the threshold hash into
`scorer_version`, so changing any of them starts a new set of assessment rows rather than mixing
scales.

Every scored answer, flagged or not, writes a `groundedness_assessment` row. A flagged answer also
writes a `groundedness_detection` row listing every sentence at or above the threshold, with its
offsets into the answer string. Answers the rules above skip write nothing: they are not trials.

## Why an answer with no documents counts

A trace scored against its prompt is a trial like any other. When a turn fetched nothing, the prompt is
the whole of what the answer had to go on, so an answer that states more than the prompt holds is
unsupported by its source in the same sense as one that states more than its documents. Leaving those
traces out would make the denominator depend on whether retrieval ran, and a call site whose retrieval
started returning nothing would drop out of its own test at the moment it most needs watching.

## The trace is the trial

The rate test is Tool Error's Bernoulli CUSUM, run per call site with a trace as the trial. A trace is
one trial on each call site it had an answer scored on, bucketed in the UTC hour its first scored answer
started, and it fails when any of its flagged answers there still has an uncleared detection. The
replay rebuilds from the tables on every pass over a 28-day window, so a trace scored across several
uploads lands in its original hour, and one cleared by a `false_alarm` resolve stops being a failure.
Only a rise is reported.

## The learned rate

Each call site starts being judged once its reference holds `min_baseline_traces` (200), and the
reference keeps learning each later hour until it holds `freeze_baseline_traces` (1,000), then stops
moving. `arl_target` is 50,000: the traces a healthy call site runs between false alarms.
`shift_floor` is 0.02, the model's own false-alarm rate at the default threshold. Judging from 200
while learning to 1,000 keeps false findings during learning low and matches waiting for 1,000
afterwards; freezing at 200 gives several times the false findings (see `GroundednessConfig`).

The model's false-alarm rate depends on the domain, and the learned reference is what absorbs it: a call
site is compared with its own past, not with a shipped number. The consequence is the same as
frustration's: a call site that answers badly from its first day learns that as its normal and is
flagged only if it gets worse. A change to the scorer or the tuning resets every call site's state
with the note `tuning changed`, because a reference learned under another threshold is not comparable.

## When the model is down

`EncoderAvailability` probes the model's `GET /healthz` on boot and every
`tessary.observer.encoder.probe-interval-ms`. Up means a `200` whose `heads` lists `groundedness`; a
bare `200` is not enough, because classify-service answers one with an empty manifest. While the model
is down, `ClassifierService` enqueues no groundedness sweep and `ClassifierWorker` hands a claimed one
back without spending an attempt. Scoring pauses and resumes from the cursor when the model answers.
The classifier, its findings and the org's switch are untouched.

`tessary.groundedness.classifier-mode` (`TESSARY_GROUNDEDNESS_CLASSIFIER_MODE`) sets how "down" is
read. In `dev`, a model on the developer's machine is swept whenever it answers. In `production`, the
AWS instance starts hourly, scores, and stops itself when idle, so after a sweep catches up groundedness
is not enqueued for `production-sleep-minutes` (30), and the model being down between runs is normal.

`GroundednessStatus` gives the classifier row one of four states, in this order:

| State | When |
|---|---|
| `off` | The row is disabled, whatever the model does. |
| `on` | Dev: the model answers. Production: a sweep caught up within `production-missed-run-minutes` (120), or the model answers before its first sweep. |
| `not_scoring` | The sweep cursor has moved once, so the model was set up, and the rule for `on` fails. |
| `not_set_up` | The cursor has never moved. |

The row's "No scores since" time is the newest `scored_at` in `groundedness_assessment`, or in
production a later caught-up sweep, since a run that found nothing new to score still ran.

## Findings go through triage

A rising call site files a `groundedness_rate` finding, and unlike frustration's it is not ruled at
filing: it goes through triage, and a positive ruling opens the case. A later pass over the same spell
refreshes the open finding's numbers and evidence. A ruled finding covers the spell up to its last hour,
and new traffic in a later hour files a new finding. A negative triage ruling does not reset the rate test, so
while the rise lasts a new finding can file each hour, as Malformed Output's do.

The evidence is the rate's two sides: every trace scored on the call site since onset as `member`, and
every flagged one as `witness`, a trace row followed by a span row for each flagged answer in it.
Neither is capped. On a case, RCA writes a `groundedness_causes` report: causes grouped by what the
agent did that left its answers unsupported, most traces first, each citing the traces that show it.

## What a resolve does

A groundedness case can be resolved with a disposition, `fixed` or `false_alarm`, or with none. Every
resolve resets the call site's state and drops the learned reference, so the call site learns its rate again from the traffic after
the resolve. `false_alarm` also sets `cleared_at` on the detection of every answer the case's findings
cite, so their traces stop counting as failures; `groundedness_assessment` is never touched, so a
cleared trace is still a trial. Unlike frustration's, a groundedness case can also be absorbed, which
re-learns the reference from the traffic after the press and closes the case. The cost of
re-learning is frustration's too: a case resolved as `fixed` before the fix lands teaches the degraded
rate as the new normal.

## What an MCP caller sees

`get_finding` and `get_case` carry the `groundedness` block with its numbers (`rate`, `flagThreshold`,
`baselineTraces`, `learningUntil`, `arlTarget`) and with `answers` emptied, so no trace or span id
leaves through it. See [auth-and-mcp.md](../reference/auth-and-mcp.md).
