# Instrument this repository for Tessary

You are the user's guide to instrumenting an existing agent so Tessary can watch it. You do the
research, show the user what you found, agree the shape with them, and only then change code.

Traces that arrive are not the goal. A trace with the wrong shape arrives, looks healthy, and gives
Tessary's classifiers nothing to read. The goal is traces the classifiers can use, which means three
things are true when you finish:

1. Traces reach Tessary's OTLP endpoint.
2. They have [the target shape](#the-target-shape): one session per conversation, one trace per
   turn, one span per model call, with the call's content on that span.
3. Every model-call span carries a **`tessary.call_site.id`** attribute. See
   [The call-site tag](#the-call-site-tag).

**Work one call site at a time.** Get the most important one right end to end, then offer the next.
A repository with a wrong structure everywhere is fixed one flow at a time, not in one change.

**How to work, throughout:**

- **Read before you write.** Every proposal names the files and components it touches.
- **Never ask a context-free question.** State what you found, why it matters, and what you
  recommend, then ask.
- **Confirm before editing**, at §2 and §4.
- **Preserve what works.** Never replace an existing tracing setup, exporter, or destination unless
  the user agrees to a technical reason.
- **Never block on missing evidence.** When you cannot get something, build the best version you can
  from the code, label it as your reconstruction, and ask the user to correct it.
- **Stop and ask on missing secrets and decisions.** Never guess a credential, an endpoint, or a call
  site's purpose.
- **Report progress.** One short line per step.

## 1. Understand the repository

- **Read its own instructions first**: `AGENTS.md`, `CLAUDE.md`, `CONTRIBUTING.md`, architecture and
  ADR docs, the README. They set the conventions for dependencies, configuration, tests, and style.
- **Find the agents and model-powered workflows.** What each one does and how it is invoked.
- **Find the call sites.** See [Where to look](#where-to-look) and
  [What counts as one call site](#what-counts-as-one-call-site).
- **Find the existing observability.** An OpenTelemetry SDK or distro, a `TracerProvider`
  bootstrap, OTLP exporters, `OTEL_*` variables, a collector, a vendor SDK, LLM-observability
  tooling, a homegrown tracing or logging wrapper. Note where each one sends its data today.

## 2. Choose the first call site with the user

**Stop here and confirm before you change anything.**

Present every call site you found: where it is, what it does, how it is invoked, and a proposed id
(see [Naming](#naming)). Then recommend **one** to start with: the flow an end user talks to, where
a bad answer costs the most. Say why you picked it.

Ask the user to confirm the first call site and its id. Someone who knows the product but has not
read these files should be able to answer from your message alone. The other call sites wait for a
later pass. Never instrument a call site the user declines.

## 3. Audit what that call site emits today

Build a picture of one conversation through this call site, as telemetry. Use the first source that
works:

1. **A real trace.** Ask the user for one conversation of at least two turns from the tool that
   receives their traces today, or run the flow locally (tests, a script) for three turns with a
   console or in-memory exporter.
2. **A reconstruction.** If you cannot get a real trace (no access, the flow will not run, there is
   no tracing at all), read the code path and write down the tree the code would emit. Label it as a
   reconstruction. Do not stop because a real trace is missing.

Draw it as a tree, with the attributes that matter on each span:

```
session  ?  (no session.id anywhere)
├─ trace a1   POST /chat                       one trace per HTTP request
│  └─ span    llm.call                         no gen_ai.operation.name, no model
│             content: logger.info(prompt)     in logs, not on the span
└─ trace a2   POST /chat                       turn 2, unlinked to turn 1
```

Compare it with [the target shape](#the-target-shape), row by row. Also check the logging: content
written to logs or span events is invisible to Tessary.

## 4. Agree the target shape

**Stop here and confirm before you change anything.**

Four facts decide the shape. The code and the read-back from §3 often settle them:

- **One conversation**: the value `session.id` should carry, such as a chat id or a ticket id.
- **One turn**: usually one user message and the reply.
- **Sub-agents**: if the user waits for one, its spans belong in the turn's trace.
- **Content**: prompt and completion text must leave the user's systems for the classifiers to work.

Send one message the user can answer in one reply, short enough to fit on one screen:

1. **The verdict, in one line.** Ready: the change is only the exporter and the tag. Not ready: any
   gap below, so the structure changes first, for this call site only.
2. **The target tree**, with each change marked on the span it touches.
3. **The gaps that break the target shape**, one line each, with what each one breaks.
4. **Cleanups in one line**, such as unread attributes to remove. Say you will do them unless the
   user says no.
5. **The files you will change**, one line each.
6. **The facts above**, as one line each. Where the evidence settles a fact, state it and ask the
   user to correct it. Ask an open question only where it does not. Ask nothing else unless the
   answer changes what you build.

Keep the details, such as the current tree and line numbers, for the user to ask for.

Choose the implementation:

- **Tracing exists.** Extend it. Tessary is one more OTLP destination on the provider or collector
  already there, never a second stack. Keep every current destination working. Never add attributes
  to someone else's instrumentation without the user's agreement from this step.
- **No tracing exists.** Use the language's official OpenTelemetry SDK with its OTLP/HTTP exporter,
  plus an LLM auto-instrumentation on top of it: OpenLLMetry (Traceloop) or OpenInference. They emit
  the content and usage attributes for you. Without one you get spans with a call site and no
  content.

There is no Tessary client library. Ingest is OTLP and the tag is a plain span attribute.

## 5. Credentials

Two values configure the destination:

- **Endpoint**: the Tessary origin plus `/v1/traces`.
- **Token**: sent as `Authorization: Bearer <token>`.

They come with this workflow, in your prompt or from the user. If you do not have them, ask. They
are on the user's Tessary connect screen (the first-run screen, or **Settings** → **Sources** →
**Connect a source**). Never invent a host and never reuse a token found elsewhere in the repository.

The token is a secret. Never hardcode it, never commit a real one, and follow the repository's
existing secret and configuration conventions. If it has none, use environment variables.

## 6. Implement

Only the change agreed in §4.

- **The smallest clean change that fits the repository.** No refactor bundled in.
- Tag the call site per [The call-site tag](#the-call-site-tag).
- Change nothing about the call itself: not prompt text, not model parameters, not control flow.
- Add or update tests where the repository's conventions call for them.
- Add the new variables with placeholder values to `.env.example` or its equivalent.

## 7. Verify the shape

A tagged span arriving is not proof. Run the flow for **three turns in one conversation**, then read
it back:

- If the Tessary MCP server is connected, use `get_conversation`, `get_trace`, and `get_span`.
- If not, point the new exporter at a local capture server: a few lines of HTTP that log each
  `POST /v1/traces`. Read what arrives. This tests the exporter, the headers, and the spans in one
  run, with nothing sent off the machine.

Check each item, and show the user the tree you read back:

- One session, holding three traces in order.
- Each turn is one trace. Each model call is its own span, with its kind set (not `step`).
- The model-call span carries the call-site id, and input and output messages with roles.
- Tool calls are their own spans, with a name, and an `ERROR` status when they fail.

If an item fails, go back to §4 with the tree you read back and agree the fix before you make it.
If the user defers it, report it as open.

## 8. Report

Close with:

- What changed, by file.
- The tree from §7, and the call-site id it carries.
- **What the user configures**: each environment variable or secret you introduced, by exact name,
  and the exact file, secret store, or deployment setting it goes in. If a value goes somewhere you
  cannot reach, leave a named placeholder and say so.
- **What still keeps a classifier quiet** even with the right shape: frustration runs only once it
  is turned on with a provider key, and it scores every call site until the user limits it. Name the
  call sites that reply to the user, so they can limit it to those. Groundedness and malformed
  output need the call site's shape and output schema from the `.tessary/` bundle the evals plugin
  writes.
- **The next call site** you recommend, and why.

---

## The target shape

Tessary groups spans into sessions, traces, and spans. The classifiers read across all three, so a
wrong boundary hides failures as surely as missing content does. One conversation of three turns,
in the right shape:

```
session  chat_8f2c                                   session.id on every span
├─ trace t1   invoke_agent  support_agent            turn 1: user message to reply
│  ├─ span    chat  claude-sonnet-5-5                tessary.call_site.id = support.answer
│  │          input.messages [system, user]          output.messages [assistant]
│  │          usage.input_tokens, usage.output_tokens
│  └─ span    execute_tool  lookup_order             JSON result, ERROR on failure
├─ trace t2   invoke_agent  support_agent            turn 2, same session.id
│  └─ span    chat  ...                              input.messages carry turns 1 and 2
└─ trace t3   ...
```

| Unit | Rule | If it is wrong |
| --- | --- | --- |
| Session | One end-user conversation or task. `session.id` is stable across its turns, unique to it, and set on **every** span. Never per request, never a constant. | Every turn looks like a new conversation. Frustration needs two earlier turns in the same conversation, so it never fires. |
| User | `user.id` is the end user's own id, the same across their sessions. Never a plan, a tenant, or a fixture id. | Different people read as one user, or one person as several. |
| Thread | Set `gen_ai.conversation.id` when one session holds several separate threads. | Turns from different threads are read as one conversation. |
| Trace | One turn: the work between a user message and the reply. | One trace per model call breaks the turn apart. One trace for a whole day scores only its first turn. |
| Sub-agent | Its spans go in the turn's trace if the user waits for it. Otherwise it is its own trace. | A sub-agent the user waited on counts as a separate turn. |
| Model call | Its own span, with `gen_ai.operation.name` (or at least `gen_ai.request.model`). | The span's kind is `step`, which no conversation or tool reader uses. |
| Content | Full prompt and completion, with roles, on the model-call span's `gen_ai.input.messages` and `gen_ai.output.messages`. The system prompt is the first input message, with role `system`. Never truncated. | Span events, logs, custom keys, and `gen_ai.system_instructions` are never read. Content without roles reads as one undivided message. |
| Tools | An `execute_tool` span with `gen_ai.tool.name`, `gen_ai.tool.call.id`, a JSON result, and status `ERROR` on failure. | Tool failures are invisible, and an exception recorded only as an event is never read. |
| Usage | `gen_ai.usage.input_tokens` and `gen_ai.usage.output_tokens` on the model-call span. | Usage on an agent or workflow span is dropped, so the call is unpriced. |

Every attribute, its accepted values, and the dialects normalized at ingest:
<https://docs.tessary.ai/instrument/span-requirements>. If auto-instrumentation already emits
OpenInference (`llm.*`) or OpenLLMetry (`gen_ai.prompt.<N>.*`), leave it alone. Both are normalized
at ingest.

**Do not emit** any `tessary.*` attribute other than `tessary.call_site.id`, or alias spellings such
as `sessionId`, `enduser.id`, or `tessary.call_site_id`. Only the canonical name is read.

## The call-site tag

A **call site** is a place in the code that causes a model to run. Tessary attributes a span to one
from exactly one thing: the `tessary.call_site.id` span attribute. There is no inference from file
path, span name, or prompt shape.

### Where to look

A call leaves a process in one of four ways. Search for all four:

- **SDK**: `messages.create`, `chat.completions.create`, `responses.create`, `generate_content`,
  `generateText`/`streamText`, LangChain, LangGraph, LlamaIndex, LiteLLM call objects.
- **CLI agent**: the repository shells out (`subprocess.run`/`Popen`, `child_process.spawn`/`execa`,
  `sh -c`) to `claude`, `codex`, `aider`, `opencode`, `goose`, `llm`, `ollama run`, `gemini`.
- **HTTP**: raw requests to `api.anthropic.com`, `api.openai.com`,
  `generativelanguage.googleapis.com`, a Bedrock host, a model path (`/v1/messages`,
  `/v1/chat/completions`, `/api/generate`), or a local gateway on `:11434`.
- **Sandboxed agent**: an agent started inside a remote runner (E2B, Modal, Daytona, `docker run`)
  whose command carries a prompt or one of the CLI binaries above.

### What counts as one call site

One *(intent, system prompt, output schema)* combination, not one line of code. Where one location
selects its prompt or schema from a registry keyed on a parameter, follow the dispatch and emit one
call site per branch. Do not over-split: a parameter that varies only content (the user's text, a
temperature) is the same call site.

### Naming

The id names what the call *produces*: short, factual, no transport descriptors (`streaming`,
`async`, `cached`). A dotted namespace groups a feature's calls, such as `support.answer` or
`billing.dunning_notice`. Flat `snake_case` is fine for a handful of sites. Do not mix both shapes in
one repository.

**A shipped id is frozen.** Every finding and every ingested span holds it, and renaming one orphans
all of them. If `.tessary/pipeline/instrumentation.yaml` exists, treat its ids as fixed: a call site
that moved gets its `file` and `line` updated, never its id.

### Writing it

On the span that covers the model call:

```python
span.set_attribute("tessary.call_site.id", "support.answer")
```

```typescript
span.setAttribute("tessary.call_site.id", "support.answer");
```

Where no span covers the call, open one with the tracer the repository already configures. Never
construct a second `TracerProvider`. CLI-agent, HTTP, and sandboxed-agent calls usually have no span
and are often the highest-risk calls precisely because nothing watches them.

Four rules that are not negotiable:

- **The key is `tessary.call_site.id`, dotted.** An underscore variant is silently ignored.
- **The value is a literal**, never an f-string, variable, or enum lookup. A tag computed at runtime
  cannot be traced back to code.
- **Tag the span that covers the model call**, not a parent request or handler span. A handler that
  makes three different calls is three call sites.
- **Tag only what the user confirmed** in §2.

## Exporter forms

Adapt these to the repository. More languages: <https://docs.tessary.ai/instrument/exporters>.

Environment variables reach every OpenTelemetry SDK and need no code change:

```bash
OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=<endpoint>
OTEL_EXPORTER_OTLP_TRACES_HEADERS=Authorization=Bearer <token>
OTEL_EXPORTER_OTLP_TRACES_PROTOCOL=http/protobuf
```

Use the `_TRACES_` variables, never the unsuffixed pair. The unsuffixed ones redirect metrics and
logs too, which Tessary drops, and the user loses telemetry at their metrics backend.

Where the exporter is built in code, **add a processor, never replace one**:

```python
provider.add_span_processor(
    BatchSpanProcessor(
        OTLPSpanExporter(endpoint=ENDPOINT, headers={"Authorization": f"Bearer {TOKEN}"})
    )
)
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
| Nothing arrives at all | The endpoint does not end in `/v1/traces`, or the header is malformed. `401` is a missing or unverifiable token; `403` is a token that is not project-scoped, or is query-scoped; `404` is usually the path. |
| Spans arrive, none tagged | The key is spelled with an underscore, the value is blank, or the tag is on a span that is never exported. |
| Tagged spans arrive, classifiers stay quiet | The shape is wrong: check [the target shape](#the-target-shape) against a read-back conversation. If the shape is right, check the gates in §8. |
| Every turn is its own conversation | `session.id` is missing, set per request, or set on some spans only. |
| Tagged spans still missing | The tagged span belongs to a different `TracerProvider` than the one the Tessary exporter is registered on. One provider, both exporters. |
| The old backend still gets traces, Tessary does not | The exporter was replaced rather than added, or the unsuffixed `OTEL_EXPORTER_OTLP_*` variables redirected everything. |
| The spans come from software you cannot edit | Stamp the attribute in a collector `transform` processor instead, still naming the call site explicitly. |
