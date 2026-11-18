# tessary-prompts

Fetch, cache, compile and link [Tessary](https://github.com/tessaryai/tessary) managed prompts from
Python. The client reads a prompt version from your instance's `GET /v1/prompts/fetch`, keeps it in
an in-process cache, fills in its variables, and links the span of your model call to the version
that produced it. It uses the standard library only and needs Python 3.10 or later.

The full guide, with the TypeScript package side by side, is
[`docs/prompts/sdk.mdx`](https://github.com/tessaryai/tessary/blob/main/docs/prompts/sdk.mdx).

## Install

The package is not on PyPI yet. From a checkout of the repository:

```bash
pip install ./packages/sdk-python          # or: uv add ./packages/sdk-python
```

The `otel` extra (`./packages/sdk-python[otel]`) installs `opentelemetry-api` for linking spans.
The SDK itself never imports OpenTelemetry.

## Quick start

Use the instance origin and a query-scoped project API key (`tsy_q_`). See
[`docs/reference/api-keys.mdx`](https://github.com/tessaryai/tessary/blob/main/docs/reference/api-keys.mdx)
for the scopes.

```python
from opentelemetry import trace
from tessary_prompts import TessaryPrompts

prompts = TessaryPrompts("https://tessary.example.com", "tsy_q_YOUR_KEY")

prompt = prompts.get_prompt("support/greeting")   # the version labeled production
text = prompt.compile(customer="Ada")

with trace.get_tracer(__name__).start_as_current_span("support.greeting") as span:
    prompt.link(span)   # sets tessary.prompt.name and tessary.prompt.version
    ...                 # call your model with `text`
```

`get_prompt(name, version=3)` reads one version and `get_prompt(name, label="staging")` another
deployment label; giving both raises `ValueError`. A variable is `{{name}}` with optional inner
spaces. `None` renders as an empty string, a variable you do not pass stays in the text, and text in
braces that is not a name (such as JSON) is left alone. `prompt.variables()` lists the names.

## Chat prompts

A chat prompt is a list of `{"role", "content"}` messages and can hold placeholders,
`{"type": "placeholder", "name": "history"}`. A list-valued keyword argument replaces the
placeholder of that name; each item must be a dict with `role` and `content`, or `compile` raises
`TypeError`. A placeholder you do not fill stays in the output.

```python
messages = prompts.get_prompt("support/answer").compile(
    question="Where is my order?",
    history=[{"role": "user", "content": "Hi"}, {"role": "assistant", "content": "Hello"}],
)
```

## Cache and fallback

- A prompt fetched in the last 60 seconds is served without a request (`cache_ttl_seconds` on the
  client or on one call changes that; `0` skips the cache).
- An older prompt is returned at once and refetched on a background daemon thread, one refetch per
  entry at a time. A failed refetch keeps the old prompt; a 404 drops it.
- `create_prompt` and `update_labels` drop every cached entry for that name.

`get_prompt(name, fallback="Hello {{customer}}")` returns the fallback instead of raising when the
fetch fails and nothing is cached, a 404 included. A string is a text prompt and a list of messages
a chat prompt. The fallback has `is_fallback=True` and version 0, is never cached, and `link` sets
nothing, so fallback traffic never counts against a version.

## Writes

`create_prompt` and `update_labels` need an admin key (`tsy_a_`). A query key gets a 403
(`PROMPTS.WRONG_KEY_SCOPE`), and an ingest key (`tsy_w_`) gets a 403 on every prompt route, reads
included. Writes are rate limited per key owner, so all keys of one user share a limit; reads are
not.

```python
admin = TessaryPrompts("https://tessary.example.com", "tsy_a_YOUR_KEY")
created = admin.create_prompt("support/greeting", "Hello {{customer}}", labels=["staging"])
admin.update_labels("support/greeting", created.version, ["production"])
```

## Errors

Every error extends `TessaryPromptsError`:

- `PromptNotFound`: the server answered 404 (`.name`, `.version`, `.label`).
- `PromptApiError`: any other refusal (`.status`, `.code`, `.message`).
- `PromptTransportError`: a timeout, a connection failure, a redirect, or an unreadable body.

Reads retry on 429, 5xx, timeouts and connection failures; writes retry on 429 only. Up to
`max_retries=2` retries wait for `Retry-After` (capped at 30 seconds) or else 0.5 seconds plus up to
0.25 seconds of jitter. Redirects are never followed, so the key only goes to the configured host.
Retries and failed refetches are logged at DEBUG on the `tessary_prompts` logger, never with the key.

## Development

```bash
bash scripts/check-sdk-python.sh   # from the repo root: uv sync, then pytest at 100% line and branch coverage
```

The suite runs against a scripted local HTTP server; no Tessary instance is needed.
