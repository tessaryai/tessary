# classifiers/ — the open classifier modules

Self-contained Python modules for the platform's open classifiers, plus the shared `framework/`
they are all built on. A classifier owner works inside one module's folder plus `framework/` and
never has to read the rest of the product.

This is **local-first**: evaluation runs on your own machine, so you can iterate cheaply before
anything touches classify-service.

> **What is here and what is not.** #1293 split this tree along the open/paid boundary. The open
> edition ships `framework/`, `tool_error/`, `metric_drift/` and `data_gen/` — the modules whose
> classifiers are pure Java in the open backend, plus the harness and the corpus emitters. The
> paid classifier modules (the encoder-head classifiers and the SOP-conformance work) live in the
> `tessary-paid` overlay and are not part of this export. `pyproject.toml`'s `pythonpath` carries
> an overlay entry for that reason; with the overlay absent it is a directory that does not exist,
> which pytest ignores.

## The shape

```
classifiers/
  framework/      # shared contracts — schema, judge, metrics, harness, scorers, agreement, audit
  tool_error/     # windowed tool-error rate detector: corpus + null eval + jshell bridge
  metric_drift/   # duration_drift / cost_drift: corpus, injection, windows + jshell bridge
  data_gen/       # OTLP corpus emitters — the trace generators the boot gates and demos run on
  tests/          # the open test suite
  data/           # generated datasets + eval sets   (gitignored)
  artifacts/      # trained model artifacts          (gitignored)
```

`tool_error` and `metric_drift` have **no model to train** — those classifiers are pure Java in
`backend/analysis/.../classifier/{tool,metric}/` — so each module carries a corpus, an evaluation
rig, and a design write-up rather than training code. Both drive the shipping Java classes over a
`jshell` bridge instead of restating their arithmetic in Python, so the floor a null run sets is a
floor on the number the Java sweep actually computes, not on a Python re-implementation of it.
Read each module's own `README.md` and `PROGRAM.md` before running anything in it.

`data_gen/` is the emitter side rather than a classifier: `data_gen.emit_local` sends real OTLP
traces at a running stack (it is what `scripts/check-open-boot.sh` uses to prove ingest works end
to end), and `data_gen/food_delivery/` generates the synthetic agent corpus the demos read.

## The framework

| piece | what it is |
|---|---|
| `schema.py` | `LabeledExample` / `EvalItem` + JSONL I/O — the common data shapes |
| `judge.py` | the LLM judge (`complete`) + generic `classify()` — labels data, no per-classifier code |
| `metrics.py` | the ship **gate** = recall at a fixed false-positive rate |
| `harness.py` | `EvalHarness` — scores a model against an eval set, reports metrics + pass/fail |
| `scorer.py` | `ClassifyServiceScorer` (the live classify-service `/classify` head) and `LocalHFScorer` (a local model) |
| `agreement.py` | annotator agreement: Cohen's/Fleiss κ, Krippendorff's α, the 0.8/0.667 bands, adjudication list |
| `audit.py` | dataset audit: cleanlab label-noise review queue, exact/near dedup (train↔eval contamination), TF-IDF shortcut control, prevalence |
| `context.py` | THE serialization contract (v1): how prior turns render to text — shared by gold-set builders and serving |
| `annotation_io.py` | Argilla bridge: push rows with judge suggestions, pull human-verified labels back, export (item, annotator, label) triples for `agreement.py` |
| `no_bedrock.py` | the guard that keeps Bedrock out of paths that must not reach it — `scripts/check-no-bedrock.sh` is its gate |

Every non-stdlib import in `framework/` beyond numpy is **lazy**, inside a function body, so the
package imports on the base dependency set and only the code path you actually run pays for its
extra.

## Setup

```bash
cd classifiers
uv sync                       # core deps (harness, judge, data scripts)
uv sync --extra quality       # + sklearn/cleanlab/statsmodels/nltk for agreement + audit
uv sync --extra otlp          # + the OTLP proto-http exporter, for data_gen
uv sync --extra annotation    # + argilla SDK for the annotation bridge
uv sync --extra train         # + torch/transformers/onnx, on a GPU box only
uv run pytest                 # the open test suite
```

Judge credentials (for labeling): set `AWS_REGION` (+ AWS creds) for Bedrock, or
`ANTHROPIC_API_KEY`. With neither, an offline `FakeJudge` runs so the pipeline still executes
(heuristic labels — not real; scripts that *generate* training data refuse to run on it silently,
you must pass `--allow-fake` to force it).

## Annotation + tracking services (dev-only, opt-in)

Argilla (annotation) and MLflow (experiment tracking) sit behind the `classifiers` compose
profile — plain `task dev` never starts them:

```bash
task classifiers:up           # Argilla -> http://localhost:16900, MLflow -> http://localhost:15000
task classifiers:down         # stops just these containers; volumes (and your data) survive
```

Dev login/SDK defaults (override in `.env`): user `argilla` / password `argilla-dev-password`,
`ARGILLA_API_URL=http://localhost:16900`, `ARGILLA_API_KEY=argilla.apikey`,
`MLFLOW_TRACKING_URI=http://localhost:15000`. Everything is loopback-bound. These services are
**never** part of the prod stack — prod is deployment-only for classifiers.

## Reaching classify-service from your host

`classify` is `expose`-only in `docker-compose.dev.yml` by default (reachable from other
containers, not from your host shell). To score against the **real running head**, publish it to
loopback once:

```yaml
# docker-compose.dev.yml, under the `classify:` service
    ports:
      - "127.0.0.1:18080:8080"
```

Then `task dev:up` (or `docker compose -f docker-compose.dev.yml up -d classify` to recreate just
that container) and point scripts at it with `CLASSIFY_URL=http://localhost:18080` and
`CLASSIFY_API_KEY=dev-classify-key` (which must match `TESSARY_OBSERVER_ENCODER_API_KEY` /
compose's dev default). This mapping is dev-only and loopback-bound — never add it to the prod
compose or the ECS task definition.

## Adding a classifier module

1. `mkdir classifiers/<name> && touch classifiers/<name>/__init__.py`.
2. Write `<name>/labels.py`: the label definition (`LabelSpec` — what the positive and negative
   class mean, in the judge's own words) and a `Gate` (start loose, tighten once measured). This
   file is the single source of truth every other script imports — evaluation and training must
   never disagree about what the label means.
3. Write `<name>/build_evalset.py`: assemble an **independent** eval set (not the shipped model's
   own training data) as `EvalItem` rows, written via `framework.write_jsonl`. Document the
   source's license in the module's README — eval-only sources (ODC-BY, CC-BY-NC-SA, and the like)
   are fine; only Apache/CC0/owned data may ever be *trained* on.
4. Write `<name>/eval_null.py` (or `eval_baseline.py` if a head already exists): point
   `ClassifyServiceScorer(head="<name>")` or `LocalHFScorer` at the harness and print the report.
   For a pure-Java classifier, drive the shipping classes over a `jshell` bridge the way
   `tool_error/bridge.jsh` and `metric_drift/bridge.jsh` do, rather than reimplementing them here.
5. Write a module `README.md`: label definition, data table (script → output → source → license),
   the gate and its rationale, and an explicit "not yet built" list so the next person does not
   re-discover gaps you already know about.
6. Only once a measurement exists and a real decision is made to ship: wire the head into
   `classify-service/models.json` and `classify.js`'s `SCORERS` map, and add the
   `ClassifierModule` manifest entry in `BuiltInClassifierCatalog.java` — one manifest, one place.

## Tests

```bash
cd classifiers && uv run --extra quality pytest tests/
```

`pyproject.toml` sets `pythonpath = [".", "../tessary-paid/classifiers"]`, in that order. `"."`
must stay first — the overlay ahead of it would shadow the open packages — and it cannot collapse
to a bare `["."]` either, because `metric_drift/export_corpus.py` spells its own import absolutely.
