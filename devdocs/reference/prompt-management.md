# Prompt management

How managed prompts are stored, written, read and linked to traces, and how the playground runs one.
The code is `promptlib/` in `analysis` and `llm/chat/` in `llm-runtime`
([architecture.md](./architecture.md)); the tables are in [data-model.md](./data-model.md) §
*Prompt library*. User-facing pages: `docs/prompts/overview.mdx`, `docs/prompts/sdk.mdx`,
`docs/prompts/playground.mdx` and `docs/reference/prompts-api.mdx`.

Everything here sits behind `Capability.PROMPT_MANAGEMENT` (`prompt_management_enabled`), on by
default. An owner or admin turns it off per organization in Settings → Features; off hides the page
and answers 403 on every prompt route.

## Tables and derived `latest`

Three tables, all cascading from `project`:

- `prompt`: one name per project (`UNIQUE (project_id, name)`), a `type` of `text` or `chat` fixed at
  creation, `tags` as a jsonb array, `last_version` (the highest version number ever handed out, never
  negative), `created_by`, `created_at`, `updated_at`.
- `prompt_version`: immutable. `version > 0`, `body` jsonb, `config` a jsonb object,
  `commit_message` of at most 500 characters, `created_via` `ui` or `api`, `UNIQUE (prompt_id, version)`.
- `prompt_label`: primary key `(prompt_id, label)` and a `version_id` FK, so a label points at one
  version at a time. A CHECK forbids `latest`.

`latest` is `MAX(version)` per prompt. It is computed on every read and appended to a version's labels
when that version is the highest. It is never stored, so deleting the top version moves it with no
write. `prompt.updated_at` moves on every version, label and tag write, which is what
`fromUpdatedAt` and `toUpdatedAt` filter on.

## Writes and concurrency

Every version and label write takes `lockPrompt` (`SELECT ... FOR UPDATE` on the prompt row) before it
touches a version or label row, removing a label included, so two writes to one prompt queue instead of
deadlocking.

- A version number is never reused. `createVersion` runs in one transaction: lock, then
  `claimVersion` raises `prompt.last_version` by one and returns it as the new number, then the insert.
  Deleting a version never lowers `last_version`, so after the top version is deleted the next version
  gets the old top number plus one. A reader pinned to a version number, the SDK cache key and the usage
  counts never see a different body under the same number. `ux_prompt_version` is the backstop: a
  `DuplicateKeyException` is 409 `PROMPTS.CONCURRENT_WRITE`.
- `create` inserts the prompt with `last_version` 1 (`DuplicateKeyException` is `NAME_EXISTS`, any
  other integrity error `INVALID_NAME`) and writes version 1 in the same transaction.
- A label move reads the label's current version with `SELECT ... FOR UPDATE OF l`, then upserts
  `ON CONFLICT (prompt_id, label)`. `LabelMode.REPLACE` (the UI's PUT) leaves the version with exactly
  the listed labels and deletes its others. `LabelMode.ADD` (the public PATCH) only moves the listed
  labels.
- A labeled version cannot be deleted (409 `VERSION_LABELED`), nor can a prompt's only version (409
  `LAST_VERSION`). A prompt holds at most 1000 versions (409 `VERSION_LIMIT`).
- Every statement is keyed on `project_id`, so another project's id reads as `NOT_FOUND`. Folder and
  search filters use `starts_with` and an escaped `ILIKE`, never a bare `LIKE`, because `_` and `%`
  are legal in names.

## Rules and caps

| Rule | Value |
|---|---|
| Name | `^(?!/)(?!.*//)(?!.*/$)[^\|\s]{1,255}$` with `UNICODE_CHARACTER_CLASS`. The Java rule is the real guard; the SQL CHECK is a backstop. A `/` makes a folder, and a folder path follows the name rule. |
| Label | `^[a-z0-9_.-]{1,36}$`. `latest` is reserved (`LATEST_RESERVED`). `production` is the label a read with neither version nor label resolves. No trimming, no lowercasing. |
| Tag | `^\S{1,64}$`, at most 50 per prompt, order kept, duplicates dropped. |
| Variable | `\{\{\s*([A-Za-z_][A-Za-z0-9_]*)\s*\}\}`: no dot, no `{{#each}}`, first-appearance order, deduplicated. Placeholders hold none. |
| Placeholder name | `^[A-Za-z_][A-Za-z0-9_]*$` |
| Body | Text: a string of 1 to 200000 characters. Chat: 1 to 200 entries, at most 200000 characters serialized. A message is `{role, content}` and accepts `type` `message` or `chatmessage` on input, stored without `type`. A placeholder is exactly `{type: "placeholder", name}`. |
| Config | A JSON object, at most 65536 characters serialized; `null` means `{}`. |
| Commit message | At most 500 characters. |
| NUL | U+0000 is refused in a name, folder, tag, body, config (keys and nested strings too) and commit message, because Postgres stores it in neither text nor jsonb, and a looked-up name holding it names no prompt. An integrity error from the version insert or the tag update is still a 400 `COMMON.INVALID_BODY`, with no SQL cause attached, because the database's error context would quote the stored JSON. |
| Request lists | `labels`, `tags`, `newLabels`: at most 50 non-blank strings. |
| Request body | A declared `Content-Length` over 1048576 bytes on `/api/orgs/*/projects/*/prompts/**` and `/v1/prompts/**` is 400 `COMMON.INVALID_BODY` from `PromptBodyLimitInterceptor`, before binding. A chunked body declares no length, so `PromptBodyLimitAdvice` reads at most 1048577 bytes of it before Jackson runs and answers the same 400 past 1048576. |
| Pages | 1-based. UI default 50, max 200; public default 10, max 100. A page whose offset would overflow is 400 `COMMON.INVALID_PARAMETER`. |

The variable regex has four copies: `PromptNames.VARIABLE`, `VARIABLE_PATTERN` in the frontend's
`promptCompile.ts`, and `VARIABLE_PATTERN` in each SDK. `scripts/check-prompt-variable-regex.sh` (the
`prompt-regex` gate) reads all four and fails when they differ.

## Routes and key scopes

UI routes live under `/api/orgs/{orgSlug}/projects/{projectSlug}/prompts` behind
`@RequiresCapability(PROMPT_MANAGEMENT)`. Reads need `ORG_VIEW`, writes and the playground `ORG_MANAGE`.
The literal segments `usage`, `folders` and `playground` win over `{promptId}`.
A version on these routes and on MCP `get_prompt` carries `created_by` (a principal id) and
`created_by_label`: the principal's display name, else its email, else `Unknown`, read in one
`PrincipalRepository.findByIds` per response. A key's write is stored under the principal that owns the key,
so the label names that person, and `created_via` says `api`. `/v1/prompts` returns neither field.

Headless routes:

| Route | Key | Does |
|---|---|---|
| `GET /v1/prompts/fetch?name&version&label` | query | One version; neither selector reads `production`. |
| `GET /v1/prompts?name&label&tag&page&limit&fromUpdatedAt&toUpdatedAt` | query | Prompt metadata, ordered by name. |
| `POST /v1/prompts` | admin | A new name makes version 1, an existing name its next version; a different type is `TYPE_MISMATCH`. |
| `PATCH /v1/prompts/labels` | admin | Moves labels (`ADD`). |
| `DELETE /v1/prompts?name&version&label` | admin | `version` deletes it, `label` removes it, neither deletes the prompt. |

`name` is always a query parameter or a body field, because Tomcat rejects `%2F` in a path. The key
scope rule is in [auth-and-mcp.md](./auth-and-mcp.md) § *One key store*: a bearer read needs a query or
admin key, a write an admin key, and a write (ingest) key gets nothing. On `/v1/prompts`, no credential
is 401 and a cookie session is 403 `PROMPTS.TOKEN_REQUIRED`. A POST that loses a create race to the same
name re-reads the name and writes the next version once, in the controller, outside the failed
transaction. Every success is HTTP 200 inside `ApiResponse`, both POSTs included. Mutating
`/v1/prompts` requests are throttled per principal by `RateLimitFilter` (429 with `Retry-After`); reads
are not.

MCP: `list_prompts(folder?, label?)` and `get_prompt(name, version?, label?)`, read-only, listed for
every token, each checking the capability on the call ([auth-and-mcp.md](./auth-and-mcp.md) § *MCP
server*).

## Wire casing exception

`/v1/prompts` is the one surface that answers in camelCase: `PromptPublicDtos` uses the field names of
a common prompt API shape (`commitMessage`, `createdAt`, `isActive`, `lastUpdatedAt`, `lastConfig`,
`totalItems`, `totalPages`), so a script written against that shape reads it unchanged. Every other prompt
DTO, the MCP tools included, is snake_case.

## Linking spans to a prompt

A span names the prompt it was produced from with `tessary.prompt.name` and `tessary.prompt.version`.
Ingest also reads two third-party prompt dialects, the observation-scoped prompt name and version keys
and then the shorter prompt name and version keys some SDKs emit, and takes the version only from the
pair that supplied the name. `gen_ai.prompt.*` is never read as a prompt
reference. The pair lands in `span.prompt_name` and `span.prompt_version` as the producer's text, so a
version may be a label such as `prod`. A name or version over 512 characters drops the link and keeps the
span. Attribute rows: [ingestion-contract](./ingestion-contract/README.md) and
[trace-schema.md](./trace-schema.md).

`GET .../traces` and the two `GET .../sessions` routes take `promptName` and `promptVersion` (ignored
without `promptName`); the Traces page's facet keys are `prompt` and `prompt_version`.

## Usage

`PromptUsageRepository` reads `span` directly, never through `PromptService`, and counts only live llm
spans inside settled, live traces, so a trace still receiving spans is not counted twice between two
reads. Both routes need `ORG_VIEW`; `days` defaults to 7 and is capped at 90.

- `GET .../prompts/usage/summary?days`: every prompt name with a call in the window, in name order.
- `GET .../prompts/{promptId}/usage?days`: one row per version string the spans sent (a null version
  included) with calls, error calls, p50 and p95 latency, tokens, cost and unpriced calls.
  `unknown_versions` names the strings that match no stored version: a label, or a version deleted since.

Both read `ix_span_prompt`, keyed `(project_id, prompt_name, started_at DESC)` with `prompt_version`
included. The per-prompt read scans only its window. The summary has no name to seek on, so it reads
every linked index entry of the project, over the whole retention period, and filters on `started_at`.

## SDK contract

`packages/sdk-python` (`tessary-prompts`) and `packages/sdk-ts` (`@tessaryai/prompts`) have no runtime
dependencies. Python uses only `urllib.request` and needs 3.10 or later; TypeScript uses global
`fetch`, and its build needs Node 18 or later. Both read `GET /v1/prompts/fetch` and write
`POST /v1/prompts` and `PATCH /v1/prompts/labels`. Neither is published yet. Gates: `sdk-python` and
`sdk-ts` in [test-suite.md](./test-suite.md).

Compile. A supplied value becomes `str(value)` or `String(value)`; `None` or `null` becomes an empty
string; `undefined`, or a name not supplied, stays literal. Inner text that is not an identifier
(`{{"a":1}}`, `{{a b}}`, `{{}}`) stays literal and is not a variable. TypeScript reads only own keys, so
`{{constructor}}` stays literal. A chat message compiles to `{role, content}` with other keys kept. A
placeholder is replaced by a supplied list of `{role, content}` objects, each compiled; an unfilled one
stays in place.

Linking. `span_attributes()` and `spanAttributes()` return the canonical pair with the version as a
string, and `{}` for a fallback. `link(span)` duck-types `set_attribute` or `setAttribute`. Neither SDK
imports OpenTelemetry.

Cache, in process. The key is `name|version:N` when a version is given, else
`name|label:<label or production>`; names cannot hold `|`, so evicting a name never hits a longer one.

- A TTL of 0 calls the loader and neither reads nor writes the cache.
- A fresh entry (age below the TTL) is returned. A stale entry is returned at once, and one background
  refresh per key starts. A successful refresh replaces the entry, a 404 evicts it, and any other failure
  keeps it, logs at DEBUG, and is retried on a later stale read.
- A miss loads on the caller's thread. Python lets two concurrent cold misses both fetch; TypeScript
  shares one request.
- The TTL defaults to 60 seconds, and a per-call TTL overrides the client's.
- `create` and `update_labels` (`updateLabels`) evict every key of that name after a 2xx, and bump a
  generation for the name: a load or refresh that started before the write stores nothing. TypeScript also
  drops the name's loads and refreshes in flight, so a caller after the write never joins a request made
  before it.

Fallback. On any SDK error from a load, 404 included, a given fallback becomes a prompt (a string is text,
a list is chat) with version 0, no labels or tags, config `{}`, and `is_fallback`/`isFallback` true. It is
never cached and never linked. Without a fallback the error is raised.

Validation is raised before any I/O (Python `ValueError`, TypeScript `TypeError`): a host that is not
`http(s)://`, an empty key or name, a version that is not an integer of at least 1, version together
with label, a negative TTL or retry count, a fallback that is neither a string nor a list, and a create
type that is not `text` or `chat` or does not match the prompt.

Wire. Headers are `Authorization: Bearer`, `Accept: application/json`, a `User-Agent` of
`tessary-prompts-python/<v>` or `tessary-prompts-ts/<v>`, and `Content-Type: application/json` on a
body. With neither version nor label the SDK sends neither. Every 2xx is success, and the SDK returns the
envelope's `data`; a 2xx without a JSON `data` object is a transport error. Errors are parsed from
`meta.error`, then the filter shape `error`, then `http_<status>`. Redirects are never followed.

Retries: attempts are `1 + max_retries` (default 2).

| Condition | GET | POST, PATCH |
|---|---|---|
| 429 | retry | retry |
| 5xx | retry | API error at once (the insert may have committed) |
| Timeout, connection failure | retry | transport error at once |
| 404 | not-found error | same |
| Other 4xx | API error with status, code and message | same |
| 3xx or any other non-2xx | transport error, no retry | same |

The wait is `Retry-After` when it is an ASCII integer, capped at 30 s, otherwise 0.5 s plus up to 0.25 s
of jitter. The last attempt raises without sleeping. The per-request timeout defaults to 5 s. Logging is
DEBUG only and never carries the key or a body.

## Playground

`POST .../prompts/playground/runs` sends one chat call, started by a person, on the org's own provider
key. It needs `ORG_MANAGE`, an admin key for a bearer caller, and `BYO_PROVIDER_KEYS` on top of
`PROMPT_MANAGEMENT`, checked in the handler because a second `@RequiresCapability` would replace the
class-level one. This is the one in-process chat call the backend makes; the exception is recorded in
[principles.md](./principles.md) and [`../../backend/AGENTS.md`](../../backend/AGENTS.md) § *LLM
integration*.

- Request: `provider`, `model`, `messages` (`{role, content}`, roles `system`, `user`, `assistant`),
  `config`, and an optional `prompt_ref` (`{prompt_id, version}`). The page compiles the edited template
  itself and sends the messages.
- Only `temperature` (0 to 2), `max_tokens` (an integer from 1 to `tessary.playground.max-output-tokens`),
  `top_p` (above 0, at most 1) and `stop` (a string, or 1 to 4 strings of at most 64 characters) are
  forwarded, in either spelling. Any other key is 400 `PLAYGROUND.UNKNOWN_CONFIG_KEY`, naming it; both
  spellings of one key is 400 `COMMON.INVALID_PARAMETER`.
- Providers: OpenAI chat completions for OpenAI, Moonshot, Gemini, GLM, Grok, Custom and OpenRouter;
  Anthropic Messages for Anthropic. Bedrock, Bedrock Mantle, TypeSafe and Platform are
  `PROVIDER_UNSUPPORTED`. `ChatProviderResolver` turns the org's credential into a target (a missing key
  is 412 `MODEL_CONFIG.MISSING_CREDENTIALS`), and `UrlGuard` checks the endpoint at call time.
- Runs in flight are bounded by `tessary.playground.concurrency`; one more is 429 `PLAYGROUND.BUSY`.
  A timeout is not retried. Keys: [config-keys.md](./config-keys.md).
- Price: a target billed by OpenRouter books OpenRouter's reported `usage.cost`; any other prices from
  the book through `PlatformCallPricer` and stamps `price_book_version`; a missing figure is unpriced. The
  view says which (`price_source` of `book`, `openrouter` or `unpriced`).
- Booking: one `llm_call` row under lane `playground`, funding `byo`, with subject
  `("prompt_version", <version id>)` when the run named one. A failed call books nothing.
- Telemetry: one `playground-run` span in Tessary's own telemetry with `gen_ai.operation.name=chat` and
  no message content ([telemetry-naming.md](./telemetry-naming.md)). Nothing is ingested into the
  project's traces, so runs do not count in prompt usage.

Which keys can run it: [`../guides/provider-keys.md`](../guides/provider-keys.md).

## Logging

`StructuredLog` with `Markers.OPS`, never a body: `prompt.version.created` (`projectId`, `promptId`,
`version`, `createdVia`, `labelCount`, `bodyChars`), `prompt.label.moved` (`projectId`, `promptId`,
`label`, `fromVersion`, `toVersion`), `prompt.version.deleted`, `prompt.deleted` (with `versions`),
`prompt.folder.deleted` (with `count`), and `playground.run.completed` (provider, model, duration,
tokens, cost, price source, prompt version id).
