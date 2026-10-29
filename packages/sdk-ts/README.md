# @tessaryai/prompts

Fetch, cache, compile and link [Tessary](https://github.com/tessaryai/tessary) managed prompts from
TypeScript or JavaScript. The client reads a prompt version from your instance's
`GET /v1/prompts/fetch`, keeps it in an in-process cache, fills in its variables, and links the span
of your model call to the version that produced it. It has no runtime dependencies and needs Node
18 or later (global `fetch`).

The full guide, with the Python package side by side, is
[`docs/prompts/sdk.mdx`](https://github.com/tessaryai/tessary/blob/main/docs/prompts/sdk.mdx).

## Install

The package is not on npm yet. From a checkout of the repository:

```bash
cd packages/sdk-ts && pnpm install && pnpm pack   # builds dist/ and writes tessaryai-prompts-0.1.0.tgz
pnpm add <path-to>/tessaryai-prompts-0.1.0.tgz    # in your application
```

## Quick start

Use the instance origin and a query-scoped project API key (`tsy_q_`). See
[`docs/reference/api-keys.mdx`](https://github.com/tessaryai/tessary/blob/main/docs/reference/api-keys.mdx)
for the scopes.

```typescript
import { trace } from "@opentelemetry/api";
import { TessaryPrompts } from "@tessaryai/prompts";

const prompts = new TessaryPrompts({ host: "https://tessary.example.com", apiKey: "tsy_q_YOUR_KEY" });

const prompt = await prompts.get("support/greeting"); // the version labeled production
if (prompt.type !== "text") throw new Error("support/greeting is not a text prompt");
const text = prompt.compile({ customer: "Ada" });

prompt.link(trace.getActiveSpan()!); // sets tessary.prompt.name and tessary.prompt.version
```

`get` returns `TextPrompt | ChatPrompt`; check `prompt.type` before you use what `compile` returns.
`get(name, { version: 3 })` reads one version and `get(name, { label: "staging" })` another
deployment label; giving both rejects with a `TypeError`. A variable is `{{name}}` with optional
inner spaces. `null` renders as an empty string, a variable you do not pass (or pass as `undefined`)
stays in the text, and text in braces that is not a name (such as JSON) is left alone.
`prompt.variables()` lists the names. `link` works on any object with `setAttribute`; the package
never imports OpenTelemetry.

## Chat prompts

A chat prompt is an array of `{ role, content }` messages and can hold placeholders,
`{ type: "placeholder", name: "history" }`. The second argument of `compile` maps placeholder names
to messages (an array-valued variable of the same name also fills one); each item must be an object
with `role` and `content`, or `compile` throws a `TypeError`. A placeholder you do not fill stays in
the output.

```typescript
const messages = (await prompts.get("support/answer")).compile(
  { question: "Where is my order?" },
  { history: [{ role: "user", content: "Hi" }, { role: "assistant", content: "Hello" }] },
);
```

## Cache and fallback

- A prompt fetched in the last 60 seconds is served without a request (`cacheTtlSeconds` on the
  client or on one call changes that; `0` skips the cache).
- An older prompt is returned at once and refetched in the background, one refetch per entry at a
  time. A failed refetch keeps the old prompt; a 404 drops it. Concurrent first reads of one entry
  share one request.
- `create` and `updateLabels` drop every cached entry for that name.

`get(name, { fallback: "Hello {{customer}}" })` returns the fallback instead of rejecting when the
fetch fails and nothing is cached, a 404 included. A string is a text prompt and an array of
messages a chat prompt. The fallback has `isFallback: true` and version 0, is never cached, and
`link` sets nothing, so fallback traffic never counts against a version.

## Writes

`create` and `updateLabels` need an admin key (`tsy_a_`). A query key gets a 403
(`PROMPTS.WRONG_KEY_SCOPE`), and an ingest key (`tsy_w_`) gets a 403 on every prompt route, reads
included. Writes are rate limited per key owner, so all keys of one user share a limit; reads are
not.

```typescript
const admin = new TessaryPrompts({ host: "https://tessary.example.com", apiKey: "tsy_a_YOUR_KEY" });
const created = await admin.create({ name: "support/greeting", prompt: "Hello {{customer}}", labels: ["staging"] });
await admin.updateLabels("support/greeting", created.version, ["production"]);
```

## Errors

Every error extends `TessaryPromptsError`:

- `PromptNotFoundError`: the server answered 404 (`promptName`, `version`, `label`).
- `PromptApiError`: any other refusal (`status`, `code`).
- `PromptTransportError`: a timeout, a connection failure, a redirect, or an unreadable body.

Reads retry on 429, 5xx, timeouts and connection failures; writes retry on 429 only. Up to
`maxRetries: 2` retries wait for `Retry-After` (capped at 30 seconds) or else 500 ms plus up to
250 ms of jitter. Redirects are never followed, so the key only goes to the configured host. Pass a
`logger` with a `debug(message, fields)` method to see retries and failed refetches; the key is never
logged.

## Development

```bash
bash scripts/check-sdk-ts.sh   # from the repo root: install, tsc, build, node:test at 100% coverage
```

The tests run the `.ts` sources directly on Node 24 or later against a local stub server; no Tessary
instance is needed.
