# classifiers/

The Python side of the open classifiers: the groundedness model server. Training, evaluation and datasets live in the experiments repo, not here.

```
classifiers/
  groundedness/   # serve.py, the groundedness model server, its wire contract and setup files
  tests/          # test_groundedness_serve.py
```

`tool_error`, `duration_drift` and `cost_drift` have no module here: they are pure Java in
`backend/analysis/.../classifier/{toolerror,metric}/`, and their designs are
[`devdocs/concepts/tool-error.md`](../devdocs/concepts/tool-error.md) and
[`devdocs/concepts/metric-drift.md`](../devdocs/concepts/metric-drift.md). Frustration has no
module either: it scores turns with a hosted decision model.

## Tests

```bash
bash scripts/check-groundedness-serve.sh
```
