# classifiers/

The Python side of the open classifiers: the groundedness model server and the OTLP trace emitter
the boot check uses. Training, evaluation and datasets live in the experiments repo, not here.

```
classifiers/
  groundedness/   # serve.py, the groundedness model server, its wire contract and setup files
  data_gen/       # emit_local.py: sends OTLP traces at a running stack
  tests/          # test_groundedness_serve.py
```

`tool_error`, `duration_drift` and `cost_drift` have no module here: they are pure Java in
`backend/analysis/.../classifier/{toolerror,metric}/`, and their designs are
[`devdocs/concepts/tool-error.md`](../devdocs/concepts/tool-error.md) and
[`devdocs/concepts/metric-drift.md`](../devdocs/concepts/metric-drift.md). Frustration has no
module either: it scores turns with a hosted decision model.

## data_gen

`data_gen.emit_local` sends real OTLP traces at a running stack. `scripts/check-open-boot.sh` runs
its `canary` corpus to prove ingest and detection work end to end.

```bash
cd classifiers
uv run --extra otlp python -m data_gen.emit_local --check
```

## Tests

```bash
bash scripts/check-groundedness-serve.sh
```
