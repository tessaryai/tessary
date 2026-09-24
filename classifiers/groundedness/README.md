# groundedness

The server for Tessary's groundedness model, `tessaryai/groundedness-classifier-v1`. It scores each
sentence of an answer for how likely it is to be unsupported by the passages the answer was given.

## What's here

| Path | What it is |
|---|---|
| `serve.py` | The model server. One file with a PEP 723 header, so `uv run` installs its dependencies and runs it straight from its URL. It needs a GPU: Apple silicon or CUDA. |
| `contract/` | One request and one response in the wire shape below. The backend's tests and `tests/test_groundedness_serve.py` both read them, so the two sides can't drift. |
| `setup/` | The setup guides for running `serve.py` on a Mac with Apple silicon or on a GPU instance. |

## The HTTP contract

```
POST /classify   Authorization: Bearer <key>
                 {"head": "groundedness", "responses": [{"passages": [...], "question": "...?", "answer": "..."}]}
              -> {"scores": [{"unsupported": p, "conflict": p, "spans": [{"start", "end", "unsupported", "conflict"}]}]}
GET  /healthz -> {"ok": true, "heads": ["groundedness"], "device", "dtype", "idle_seconds"}
```

`/healthz` needs no key. The backend counts the model as up only when `/healthz` answers 200 and lists
`groundedness` in `heads`. See [`contract/`](contract/) for a full example of each body.

## Running it

Follow a setup guide in `setup/`. It downloads `serve.py` from the release tag of the Tessary you run,
checks it against the checksum in the guide, and sets `TESSARY_OBSERVER_ENCODER_URL` and
`TESSARY_OBSERVER_ENCODER_API_KEY` for the backend.

## Training and quality

- Training, evaluation, and benchmarks live in the experiments repo:
  <https://github.com/tessaryai/experiments/tree/main/groundedness-token-v1>
- The model's measured quality and its pinned revision:
  [`devdocs/reference/classifier-quality.md`](../../devdocs/reference/classifier-quality.md)
