# Instrument this repository for Tessary

You are instrumenting an existing repository so its agent traces reach Tessary. Work out how *this*
repository is built, agree the boundaries with the user, then make the smallest change that fits it.

Two things have to be true when you are done, and they are the only fixed requirements here:

1. Traces reach Tessary's OTLP endpoint.
2. Every span covering a model call carries a **`tessary.call_site.id`** attribute — see
   [The call-site tag](#the-call-site-tag). A span without one doesn't trigger Tessary's classifiers.

Everything else — which SDK, where the exporter is built, where configuration lives — is decided by
what you find, not by this file.

**How to work, throughout:**

- **Read before you write.** Every proposal names the concrete files and components it touches.
- **Never ask a context-free question.** State what you found, why the decision matters, what you
  intend to do, then ask.
- **Confirm before editing**, at the two points below that say so.
- **Preserve what works.** Never replace an existing tracing setup, exporter, or destination unless
  there is a technical reason the user has agreed to.
- **Report progress.** One short line per step.
- **Stop and ask on any blocker.** Never guess a credential, an endpoint, or a call site's purpose.

## 1. Understand the repository

Before anything else, work out what this repository *is*.

- **Read its own instructions first**: `AGENTS.md`, `CLAUDE.md`, `CONTRIBUTING.md`, architecture and
  ADR docs, the README. They tell you the conventions the rest of this workflow has to obey —
  dependency management, configuration, testing, code style, what is off-limits.
- **Find the agents and model-powered workflows.** What are they, what does each one do, how are
  they invoked?
- **Find the call sites** — the places where an agent execution begins, or where a model call is
  made. See [Where to look](#where-to-look) for the four ways a call leaves a process, and
  [What counts as one call site](#what-counts-as-one-call-site) for how finely to split them.

Understand the architecture before you name boundaries. A repository with one agent behind a
dispatcher and a repository with six independent workers need different instrumentation boundaries,
and only the code tells you which one you have.

## 2. Confirm the call sites with the user

**Stop here and confirm before you change anything.**

Present what you found:

- Each candidate call site: where it is, what it does, how it is invoked.
- Why that location is the right instrumentation boundary — why the span belongs there and not at
  its caller or its callee.
- Anything you deliberately excluded, and why.
- A proposed id for each (see [Naming](#naming)).

Then ask the user to confirm the set. Give them enough to answer without reading the code
themselves: someone who knows the product but has not looked at these files should be able to say
yes, no, or "not that one" from your message alone.

Do not instrument a call site the user declines.

## 3. Inspect the existing observability

Establish how traces are created, propagated, and exported today, before proposing anything.

Look for: an OpenTelemetry SDK or distro, a `TracerProvider` bootstrap, OTLP exporters, auto- or
manual instrumentation, `OTEL_*` environment variables, a collector or agent sidecar, an
observability vendor's SDK, LLM-observability tooling, a homegrown tracing or logging wrapper,
middleware that opens spans per request, and how context is propagated across process or task
boundaries.

Answer four questions before moving on: **is there a tracer**, **who exports to where**, **do the
call sites from §2 already sit inside a span**, and **what do those spans already carry** (see [What
a span must carry](#what-a-span-must-carry)).

## 4. Choose the implementation

### If no suitable tracing setup exists

- Tell the user plainly that you found none, and what you looked for.
- **Use the language's official OpenTelemetry SDK**, plus its OTLP/HTTP exporter. Same spec, same
  wire format in Python, JS/TS, Go, Java, Ruby, .NET, PHP and Rust, so this is the portable answer
  rather than a Tessary-specific one, and the traces stay vendor-neutral. OTLP is a plain HTTP POST,
  so a span *can* be hand-rolled with no dependency at all — but you would also be hand-rolling id
  generation, parent/child linkage, batching, retry and context propagation. Do that for a one-shot
  script, never for a service.
- **Add an LLM auto-instrumentation on top of it** — OpenLLMetry (Traceloop) or OpenInference. Both
  are OTel-native, so they attach to the provider you just configured rather than forming a second
  stack, and both emit the message and usage attributes in [What a span must
  carry](#what-a-span-must-carry) for you. Without one you get spans with a call site and no
  content, and the classifiers have nothing to read.
- Explain the approach before you add it: which SDK, which auto-instrumentation, where the bootstrap
  will live, how it gets configured, what gets wrapped in a span.
- Instrument the confirmed call sites and export their traces to Tessary.
- Follow the repository's conventions for dependencies, configuration, environment variables,
  module layout, and tests. A tracing bootstrap that ignores the repository's own structure is a
  change the maintainers will have to redo.

### If tracing already exists

- Tell the user what is already there and where traces currently go.
- Explain the specific change needed to send the relevant traces to Tessary **as well**.
- **Extend the existing pipeline. Do not introduce a second instrumentation system.** If the
  repository has OpenTelemetry, Tessary is one more OTLP destination on the provider or collector it
  already has, not a parallel stack.
- Keep every current destination working. Adding an exporter must not remove, reroute, or degrade
  the one that is there.
- **Check what the existing spans actually carry** against [What a span must
  carry](#what-a-span-must-carry). A tracing stack built for latency and errors often records
  nothing about the model call itself.
- **Do not silently add attributes to someone else's instrumentation.** List what is missing,
  separated into required and recommended, say what each one unlocks and what it costs to emit —
  message attributes carry prompt and completion text, which is a real decision for them — then ask.
  Add the required ones only once they confirm, and the recommended ones only if they want them.
- If the call sites are already inside spans, the tagging in §6 may be the entire code change.

Do not install a Tessary client library. There isn't one. Ingest is OTLP and the tag is a plain span
attribute, so anything beyond a tracer is unnecessary.

## 5. Credentials

Two values configure the destination:

- **Endpoint** — the Tessary origin plus `/v1/traces`.
- **Token** — sent as `Authorization: Bearer <token>`.

They are provided with this skill: in the prompt you were given, or from the user. If you do not have
them, ask — they are on the user's Tessary connect screen (the first-run screen, or
**Settings** → **Sources** → **Connect a source**). Never invent a host and never reuse a token found
elsewhere in the repository.

Handling:

- **The token is a secret.** Never hardcode it in application source, and never commit a real one.
- Follow the repository's existing secret and configuration conventions — `.env`, a settings module,
  a secret store, deployment manifests, whatever it already uses.
- If it has no convention, introduce environment-variable-based configuration.
- Configure the **endpoint** the same way when that fits the repository better than a literal; it is
  not a secret, but a deployment usually wants it configurable.

## 6. Implement

Only after §2's confirmation, and after telling the user what §4 chose.

- **The smallest clean change that fits the repository.** No redesign, no refactor bundled in.
- Tag every confirmed call site per [The call-site tag](#the-call-site-tag).
- Change nothing else about the calls: not prompt text, not model parameters, not control flow.
- Add or update tests where the repository's conventions call for them.
- Update example configuration — `.env.example` and its equivalents — with the new variables and
  placeholder values.

## 7. Tell the user what to configure

Assume they do not know where secrets live in this repository. Be exact:

- The precise names of the environment variables, config keys, or secrets you introduced.
- The exact file, secret store, or deployment setting each one goes in, by path.
- Where the example configuration is, if you added any.
- That the bearer token is a secret and must not be committed.

If a value has to be pasted somewhere you cannot reach, say so and leave a named placeholder.

## 8. Report

Close with:

- What changed, by file.
- Which traces now reach Tessary, and which call sites are tagged with which ids.
- What you skipped or excluded, and why.
- What the user still has to configure themselves, from §7.
- How to verify: a tag becomes telemetry only when the code runs, so exercise the app — the test
  suite is usually enough — and watch the Tessary connect screen, which updates itself and opens on
  its own when the first tagged span lands.

---

## The call-site tag

A **call site** is a place in the code that causes a model to run. Tessary attributes a span to one
from exactly one thing: the `tessary.call_site.id` span attribute. There is no inference from file
path, span name, or prompt shape — guessing would mis-attribute production traffic.

### Where to look

A call leaves a process in one of four ways. Search for all four:

- **SDK** — in-process provider or framework calls: `messages.create`,
  `chat.completions.create`, `responses.create`, `generate_content`, `generateText`/`streamText`,
  LangChain / LangGraph / LlamaIndex / LiteLLM call objects.
- **CLI agent** — the repository shells out (`subprocess.run`/`Popen`,
  `child_process.spawn`/`execa`, `sh -c`) to `claude`, `codex`, `aider`, `opencode`, `goose`, `llm`,
  `ollama run`, `gemini`.
- **HTTP** — raw requests (`requests`/`httpx`/`fetch`/`axios`) to `api.anthropic.com`,
  `api.openai.com`, `generativelanguage.googleapis.com`, a Bedrock host, a model path
  (`/v1/messages`, `/v1/chat/completions`, `/api/generate`), or a local gateway on `:11434`.
- **Sandboxed agent** — an agent started inside a remote runner (E2B, Modal, Daytona, `docker run`)
  whose command carries a prompt or one of the CLI binaries above.

### What counts as one call site

One *(intent, system prompt, output schema)* combination — not one line of code. Where a single
location selects its prompt or schema from a registry keyed on a parameter, follow the dispatch and
emit one call site per branch. Do not over-split: a parameter that varies only content — the user's
text, a temperature — is the same call site.

### Naming

The id names what the call *produces*: short, factual, no transport descriptors (`streaming`,
`async`, `cached`). A dotted namespace groups a feature's calls and is the default worth taking —
`support.answer`, `billing.dunning_notice` — with flat `snake_case` fine for a handful of sites. Do
not mix both shapes in one repository.

**A shipped id is frozen.** It is the key every grader, every finding, and every already-ingested
span holds; renaming one orphans all of them silently. If `.tessary/pipeline/instrumentation.yaml`
exists, read it and treat its ids as fixed — a call site that moved gets its `file` and `line`
updated, never its id.

### Writing it

On a span that already wraps the call:

```python
span.set_attribute("tessary.call_site.id", "support.answer")
```

```typescript
span.setAttribute("tessary.call_site.id", "support.answer");
```

Where no span covers the call, open one with the tracer the repository already configures — never
construct a second `TracerProvider`. CLI-agent, HTTP, and sandboxed-agent calls usually have no span
and are often the highest-risk calls in a repository precisely because nothing watches them; do not
skip one for being awkward to wrap.

Four rules that are not negotiable:

- **The key is `tessary.call_site.id`, dotted.** An underscore variant is silently ignored: the span
  looks tagged and resolves to nothing.
- **The value is a literal** — never an f-string, variable, or enum lookup. A tag computed at runtime
  cannot be traced back to code.
- **Tag the span that covers the model call**, not a parent request or handler span. A handler making
  three different calls is three call sites; one tag on the handler collapses them.
- **Tag only what the user confirmed** in §2.

## What a span must carry

The call-site tag says *which* call this was. These attributes say *what happened* in it, and they
are what Tessary reads. The vocabulary is the OpenTelemetry GenAI semantic conventions — do not
invent a name where a standard one exists, and do not put content under a `tessary.*` name.

Ingest is fail-open: a missing attribute degrades a feature, it never drops the span. So "required"
below means required for the span to be *usable*, not for it to be accepted.

**Required:**

| Attribute | Carries |
| --- | --- |
| `tessary.call_site.id` | The call site. Nothing resolves without it. |
| `gen_ai.operation.name` | What kind of call this is: `chat`, `text_completion`, `generate_content`, `embeddings`, `execute_tool`, `retrieval`, `create_agent`, `invoke_agent`, `invoke_workflow`. The single source of a span's kind. |
| `gen_ai.input.messages` | The prompt, JSON `[{role, parts:[{type, content}]}]`. Stored whole, never truncated. |
| `gen_ai.output.messages` | The completion, same shape, carrying `finish_reason`. |
| `gen_ai.request.model` | The requested model id. What pricing resolves against, and the backstop that makes an otherwise unkinded span an LLM call. |
| `gen_ai.usage.input_tokens` and `gen_ai.usage.output_tokens` | The token buckets. Emit both — a lone bucket prices nothing. Add `gen_ai.usage.cache_read.input_tokens` / `gen_ai.usage.cache_creation.input_tokens` wherever the provider reports them, or cached traffic reads as full-price traffic. |
| `gen_ai.usage.cost` | The provider's own cost for the call, in USD, whenever the response carries one. A reported cost is stored verbatim and never repriced, so it beats anything Tessary can infer. Per-bucket figures are better still: `llm.cost.prompt`, `llm.cost.completion`, `llm.cost.prompt_details.cache_read`, `llm.cost.prompt_details.cache_write`. Send nothing and the call is priced from its tokens; send neither and it is unpriced, never zero. |
| `session.id` | The session identity, and the spine every trace and span hangs off. The standard OTel attribute, not re-namespaced. |
| `gen_ai.provider.name` | The provider — `anthropic`, `openai`, `google`. |
| `gen_ai.tool.name` and `gen_ai.tool.call.id` | On `execute_tool` spans: the tool invoked and the provider's correlation id for the call. Without them a tool call is an unnamed span. |

**Recommended** — each one turns on something real:

| Attribute | Turns on |
| --- | --- |
| span `status.code = ERROR` (+ `error.type`) | The **only** source of failure on a span. An exception the code catches and swallows is invisible unless the status is set. |
| `gen_ai.conversation.id` | Thread grouping within a session. |
| `user.id` | The end-user handle on the session, trace and span. |
| `gen_ai.tool.type = extension` | Marks a tool call as MCP rather than a local function. |
| `retrieval.documents.<N>.document.id` / `.content` / `.score` | First-class retrieved-document rows on `retrieval` spans. |

**Do not emit:**

- **Any `tessary.*` name other than `tessary.call_site.id`.** There are no other readers. Session id
  goes in `session.id`, the user in `user.id`, nesting is derived from `parent_span_id`, kind goes in
  `gen_ai.operation.name`, and an error goes in the span status.
- **Alias spellings.** Only the canonical name is read: not `sessionId`, not `enduser.id`, and not
  `tessary.call_site_id` with an underscore.

**If auto-instrumentation is already emitting a dialect, leave it alone.** OpenInference (`llm.*`,
`openinference.span.kind`, `llm.token_count.*`) and OpenLLMetry's flattened
`gen_ai.prompt.<N>.*` / `gen_ai.completion.<N>.*` are both normalized to the canonical vocabulary at
ingest. Translating them by hand adds work and risk for no gain.

## Reference: exporter forms

Adapt these to the repository — they are the shapes, not the prescription.

Environment variables reach every OpenTelemetry SDK and need no code change:

```bash
OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=<endpoint>
OTEL_EXPORTER_OTLP_TRACES_HEADERS=Authorization=Bearer <token>
OTEL_EXPORTER_OTLP_TRACES_PROTOCOL=http/protobuf
```

Use the `_TRACES_` variables, never the unsuffixed pair: the unsuffixed ones redirect metrics and
logs too, which Tessary drops, and the user loses telemetry at their metrics backend without knowing
why.

Where the exporter is built in code, **add a processor, never replace one**:

```python
provider.add_span_processor(
    BatchSpanProcessor(
        OTLPSpanExporter(endpoint=ENDPOINT, headers={"Authorization": f"Bearer {TOKEN}"})
    )
)
```

```typescript
provider.addSpanProcessor(
  new BatchSpanProcessor(
    new OTLPTraceExporter({ url: ENDPOINT, headers: { Authorization: `Bearer ${TOKEN}` } }),
  ),
);
```

```go
exporter, err := otlptracehttp.New(ctx,
    otlptracehttp.WithEndpointURL(endpoint),
    otlptracehttp.WithHeaders(map[string]string{"Authorization": "Bearer " + token}),
)
provider.RegisterSpanProcessor(sdktrace.NewBatchSpanProcessor(exporter))
```

Where a collector already runs, fan out there and change nothing in the application:

```yaml
exporters:
  otlphttp/tessary:
    traces_endpoint: <endpoint>
    headers:
      Authorization: "Bearer <token>"

service:
  pipelines:
    traces:
      exporters: [existing_exporter, otlphttp/tessary]
```

## Troubleshooting

| Problem | Cause |
| --- | --- |
| Nothing arrives at all | The endpoint does not end in `/v1/traces`, or the header is malformed. `401` is a bad or unminted token; `404` is usually the path. |
| Spans arrive, none tagged | The key is spelled with an underscore, the value is blank, or the tag is on a span that is never exported. |
| Tagged spans arrive, but classifiers stay quiet | The spans carry a call site and no content. Check the required rows in [What a span must carry](#what-a-span-must-carry). |
| Tagged spans still missing | The tagged span belongs to a different `TracerProvider` than the one the Tessary exporter is registered on. One provider, both exporters. |
| The old backend still gets traces, Tessary does not | The exporter was replaced rather than added, or the unsuffixed `OTEL_EXPORTER_OTLP_*` variables redirected everything. |
| The edit is in, nothing changed | The application did not reload. |
| The spans come from software you cannot edit | Stamp the attribute in a collector `transform` processor instead — still naming the call site explicitly, one layer out. |
