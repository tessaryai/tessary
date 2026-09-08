# evals plugin — changelog & migration notes

This document summarizes every change to the `evals` plugin since the initial commit (`92a4802`). The contract and output-format notes are written for **consumers of the `.tessary/` bundle** — the teams whose runners, viewers, CI integrations, or curation tools read `.tessary/pipeline/*.yaml` and `.tessary/graders/*.yaml` — and tell you what your code needs to change to consume the current format cleanly.

> **TL;DR (v0.27.1, current — docs only: derive-sop's frame-exemplar guidance now teaches the full obligation frame — opening, persistence, carve-outs — measured on two rule families.)**
> The evals-platform's B1 and B3/B4 relabel experiments (e28/e29) measured where SOP annotation conventions actually diverge from human judgement: not on what *triggers* an obligation, but on **persistence turns** (wrap-ups and process questions inside an opened interaction stay owed) and **agent-side openings** (the agent volunteering a position opens the obligation exactly as an ask does). Closing that frame gap moved label agreement 0.82–0.88 → 0.96–0.975 on three rules. `derive-sop`'s style-law 3 and exemplar-harvesting sections now say a complete frame answers three questions — what opens the obligation, how long it persists, and the carve-outs — and that frames are per-rule: exemplars attach to observations, so two rules needing different frames for the same behavior means splitting the observation. `reconcile-sop`'s per-observation checklist mirrors it (conditional on the instruction surface supporting the scenarios, same as derive-sop). No code, contract, schema, or linter changes. If you pin the marketplace `ref`, bump it to `evals-v0.27.1`.

> **TL;DR (v0.27.0 — the platform's MCP surface becomes READ-ONLY and loses six tools. `propose_grader_edit`, `run_triage`, `get_triage`, `latest_triage`, `list_rca_reports` and `get_rca_report` are GONE; four plural readers (`list_traces`, `list_spans`, `list_sessions`, `get_session`) and `describe_dataset` arrive; `list_cases` becomes paged and filterable; a case's root-cause report now reads INLINE on `get_case`. REQUIRES the matching evals-platform release. Docs-only in this repo: no script logic, contract, or schema change; grader contract still v9; on-disk schema still `0.16.0`.)**
> - **Why.** v0.26.0 fixed the *symptom* — three hand-maintained copies of the tool list, all drifted — by pointing at `tools/list` as the authority. The platform then fixed the surface itself, and the shape of the fix is worth knowing even though `tools/list` remains the contract: the catalogue is now **21 tools, 19 open and 2 gated**, every one of them a read. Two things had been wrong at the design level. The surface could *act* — start a platform-paid agent session, land an edit in a human curator's queue — while presenting itself as a way to look at a project. And it was missing the plural half of every reader: you could `get_trace` an id someone handed you, but there was no way to find the id, so an agent's only route into the substrate was `query_search`'s compact metadata.
> - **The surface writes nothing now, and that is stated on connect.** `initialize` opens with it: *nothing you call can write, spend, or start an agent run.* **`propose_grader_edit` is gone** — it looked like the careful version of a write because a human accepted the edit, but a curator's queue is a person's attention and the grader body is the ruler the whole product measures against; an agent editing it is an agent moving the ruler. **`run_triage` is gone** — it started a platform-paid agent session with nothing in front of it. **`get_triage` / `latest_triage` are gone** with it: pollers for a run nothing could start. Editing a grader, resolving a case and triggering a triage run are UI actions, where the decision and the person are in the same place.
> - **`list_rca_reports` / `get_rca_report` are gone, and the report is not.** It reaches you **inline on the case that owns it**: `get_case` returns an `rca` object with the verdict, the hypotheses, the checks the agent ruled out, and its full written investigation. `rca` is `null` while one is still running (`rca_report_id` names it, so poll) and when none has been run; `rca_available` says whether one *could* be. The report is the answer to "why is this case open", so needing a second, separately-gated tool to read it was the wrong shape — and this also **retires the RCA capability gate on MCP entirely**: an org without the flag simply has no report rows to inline. If you scripted either tool, read `get_case` instead.
> - **Four plural readers, so finding no longer requires already knowing.** `list_traces` (filter by model, kind, call site, environment, status, keyword, or a `started_at` range), `list_spans` (nine typed filters, keyword *or* semantic search, at the step grain — one LLM call, tool call or sub-agent), `list_sessions` and `get_session` (one continuous interaction with one user, with its totals summed from its traces). All four page: `limit` (default 50, max 100) and `cursor` in, `next_cursor` out; a stale cursor restarts at the newest page rather than erroring.
> - **Lists find, gets read — and asking for payloads unscoped is an error, not a quiet trim.** List rows carry typed columns plus the stored `input_preview`/`output_preview` and a `payload_available` boolean, never the full text: fifty conversations is a context window spent before you have decided which trace you care about. Raw text comes from `get_span` / `get_trace`, or from `list_spans` with `fields: ["payload"]` — allowed **only** on a page already narrowed to one `trace_id` or to a range of at most 24h, and a tool error naming the rule otherwise. That error is deliberate: an agent that asked for payloads and silently got previews would reason over truncated text as if it were the conversation.
> - **`get_trace` stops promising every span.** It now returns at most **200**, oldest-first so the conversation's head survives, with `spans_truncated` saying when there were more and `span_count` giving the true total. Past the cap, `list_spans` with `trace_id` pages the rest. Previously a 200-span trace returned every span with every full payload.
> - **`list_cases` is a filtered, paged list instead of three fixed buckets.** `state` (`open` default, worst-first by severity then recency; `muted`; `resolved`, which pages newest-closure-first), `detector`, `call_site_id`, plus paging. **The coverage block moved to `get_project`** as `watching` — enabled classifiers, call sites swept, `traces_last_day`. Read it before calling an empty case list an all-clear: `traces_last_day: 0` means nothing is *arriving*, which is a different answer from nothing being wrong. **If you read the coverage numbers off `list_cases`, they are on `get_project` now.**
> - **`describe_dataset`, and the `query_*` descriptions stop reciting field names.** Ask it (optionally for one dataset) and it answers with each dataset's facetable fields, filterable fields, whether it supports search, its time column and its measure — read off the platform's own dataset catalogue rather than a prose list, which is the copy that kept going stale. Read it instead of guessing: an unknown field is an error, and the fields differ per dataset.
> - **`query_search` narrows to `tool_calls` and `classifier_events`.** Spans left the enum: `list_spans` searches the same rows with more filters, both retrieval modes, and full payloads when the call is scoped, so two tools answered one question and a model had no basis for choosing. `query_count` / `query_timeseries` / `query_facets` still reach all four datasets including `spans`, and **the REST query API is unchanged** — only the MCP schema narrowed.
> - **`GRADERS` is the last gate.** `list_graders` and `get_grader`, both reads; what sits behind the gate is the calibrate half's *content*, not a privileged action. Everything else — project, taxonomy, cases and their reports, findings, the query tools, the substrate readers — is open to every org, because a partner's own traffic and the cases raised on it are the launch product, not an upsell.
>
> **Migration.** Upgrade the plugin and the platform **together** — this is a hard cutover with no aliases, no shims and no version negotiation, the third such entry after v0.25.0 and v0.26.0. Nothing in *this* repo calls the tools (`platform.py` speaks REST and never JSON-RPC, and its behaviour is unchanged — only its docstring), so no script here breaks. If you scripted the MCP tools yourself, six calls now read as `unknown tool`: replace `list_rca_reports`/`get_rca_report` with `get_case`'s inlined `rca`, and drop the rest — triage runs and grader edits are UI actions. Two shapes also changed under existing names: `list_cases` returns `{cases[], next_cursor}` with no coverage block (it is `get_project`'s `watching` now), and `query_search(dataset: "spans")` is rejected (use `list_spans`). Nothing else to do — no bundle, grader, or on-disk change; grader contract stays **v9**, on-disk schema stays `0.16.0`, and every existing `.tessary/` bundle validates and imports byte-identically. If you pin the marketplace `ref`, bump it to `evals-v0.27.0`.

> **TL;DR (v0.26.0 — the advertised MCP tool list stops being hand-maintained prose. `reload_pipeline` is GONE from the platform, `list_pipelines` is now `get_project`, and the cases / findings / raw-payload tools are named for the first time. REQUIRES the matching evals-platform release. Docs-only in this repo: no script logic, contract, or schema change; grader contract still v9; on-disk schema still `0.16.0`.)**
> - **Why.** This plugin advertised the platform's MCP tools in three hand-maintained places — the README table, `skills/connect/SKILL.md` step 3, and `platform.py`'s docstring — and all three had drifted from what the server publishes. Two directions, both bad. It told the agent to call **`reload_pipeline`** ("Refresh after an import"), which has been a **no-op since the platform's phase D**: it answers `valid: true` with the counts the pipeline already had, so an agent that pushed a bundle and probed it read the shim as proof the import landed. And it never mentioned **`get_span` / `get_trace`** — the only tools that return raw conversation text, the ones that turn "a classifier flagged something" into a readable cause — so an agent stopped at the compact metadata `query_search` returns. The platform release deletes the no-op; these docs now point at `tools/list` as the authority.
> - **`reload_pipeline` is removed from the platform.** Nothing in this plugin *called* it — `platform.py` speaks REST (`/v1/query/facets`) and never JSON-RPC — so no script here breaks. If you scripted the tool yourself, drop the call: `POST .../import` reports its own result and is the only honest answer to "did the import land".
> - **`list_pipelines` → `get_project`, and it is no longer gated.** A token binds to exactly one project, so the old name promised a collection and the response wrapped one row in a one-element `pipelines` list. `get_project` returns the project itself, flat — **if you indexed `structuredContent.pipelines[0]`, read the object directly now.** It also used to sit behind the graders capability, which made "what am I connected to?" unanswerable for orgs that do not hold it.
> - **Named for the first time: `list_cases` / `get_case`.** Cases are what the platform's Triage surface is organised around — open cases worst-first, plus the coverage behind an all-clear (how many classifiers are enabled, how many call sites they watch, traces in the last 24h). Start there for "what is wrong with this project right now" instead of reconstructing it from aggregates; an empty case list with `traces_last_day: 0` means nothing is *arriving*, which is a different answer from nothing being wrong. Also newly documented: `list_findings` (classifier drift findings, which `get_finding` previously had no way to enumerate), `get_span` / `get_trace`, and `list_rca_reports` / `get_rca_report`.
> - **`query_*` reaches a fourth dataset and a second search mode.** The platform derives its dataset enum from its own catalogue now rather than restating it, so `metric_rollups` (pre-aggregated usage/cost) is reachable — cost-per-day questions no longer need REST. `query_search` also advertises `mode: semantic` (cosine-kNN, for "find rows like this one when you do not know the words used"); it is excluded from `metric_rollups`, which has no text to match.
> - **The tool list is documented as per-token, not fixed.** Every tool declares a capability the org must hold to be offered it, so `tools/list` is shorter for most projects and a withheld tool reads as an *unknown tool* rather than a permission error. `skills/connect/SKILL.md` now instructs the agent to read `tools/list` and report what it returns instead of naming tools from memory — the practice that produced this entry.
>
> **Migration.** Upgrade the plugin and the platform **together**, as with v0.25.0. Two changes bite a caller who scripted the tools directly: `reload_pipeline` no longer exists, and `list_pipelines` is `get_project` with a flat body instead of a one-element list. Nothing else to do — no bundle, grader, or on-disk change; grader contract stays **v9**, on-disk schema stays `0.16.0`, and every existing `.tessary/` bundle validates and imports byte-identically. `platform.py`'s behaviour is unchanged (its docstring is not). If you pin the marketplace `ref`, bump it to `evals-v0.26.0`.

> **TL;DR (v0.25.0 — the coverage read names the `spans` dataset; the platform retires the `observations` alias in the SAME release. REQUIRES the matching evals-platform release — this is a hard cutover with no grace period. No contract or schema change; grader contract still v9; on-disk schema still `0.16.0`.)**
> - **The one functional change.** `platform.py`'s `_call_site_facets` — the single request behind `platform.py envs` and `platform.py coverage` — POSTed `{"dataset": "observations", "field": "call_site_id", …}` to `/v1/query/facets`. `observations` was the v1 substrate's name for a span row; the platform's canonical wire name is `spans`, and `observations` has been a deprecated read alias resolving to it. This release sends the canonical name. Nothing else about the request changes — same endpoint, same `call_site_id` facet field (still an allow-listed dimension on `spans`), same `environment_id` filter, same `top_n`, same null-bucket-is-untagged handling. It is the only query the plugin issues, so it is the only place the stale vocabulary reached the wire.
> - **Hard cutover — ship this WITH the platform release, not after it.** The matching evals-platform release drops the `observations` alias outright. Two directions, both real: this plugin needs that platform release, and — the one that actually bites — **plugin v0.24.0 and older break against the post-cutover platform**. Their facets call names a dataset the server no longer resolves, comes back `400`, and `platform.py envs` / `platform.py coverage` exit `2`. That takes `/evals:connect` down (it lists environments as its closing report) and `/evals:instrument` with it (it reads `envs`, then `coverage --env <slug>` to verify its tags actually became telemetry). There is no fallback and no version negotiation — upgrade the plugin.
> - **Docs sweep — the user-facing copy says spans and classifier events.** README and `skills/connect/SKILL.md` described the `query_*` tools as reading "observations, tool calls, feedback, signal events". The datasets are `spans` / `tool_calls` / `classifier_events`: there is no `feedback` dataset, and "signal events" predates the signals→classifiers rename. `output_format.md`'s call-site shard now says the Malformed Output classifier validates each **span's** output. Deliberately **not** swept: Langfuse's own API names (`update_current_observation`, `@observe(name=)`) — a third party's vocabulary, not ours — and the v3 SOP schema's `observations:` concept, which is a different word for a different thing (an atomic behavioural predicate, not a telemetry row) and is untouched by any of this.
> - **`brand_voice_signals` items are keyed `label`.** `output_format.md` showed `- signal: <string>` while `contract/AUTHORING_CONTRACT.md` documented the same list as `[{label, evidence}]` — two contract documents disagreeing on one key. The platform arbitrates: `EvidencedSignal` is `record EvidencedSignal(String label, String evidence)` with `@JsonProperty("label") @JsonAlias({"kind","regime","signal"})`, so `label` is canonical and `signal` merely still parses. The example is corrected. Existing bundles are unaffected — that alias is a live deserialization alias, not a deprecation — so this is a documentation fix, not a format change. The list's own name is unchanged: `brand_voice_signals` is the English word, not classifier vocabulary.
>
> **Migration.** Upgrade the plugin and the platform **together**. This release requires the evals-platform release that retires the `observations` alias, and older plugin builds fail their `envs`/`coverage` reads against that platform (HTTP `400` → exit `2`), which breaks `/evals:connect` and `/evals:instrument`. Nothing else to do: no bundle, grader, or on-disk change — grader contract stays **v9**, on-disk schema stays `0.16.0`, and every existing `.tessary/` bundle validates and imports byte-identically. If you pin the marketplace `ref`, bump it to `evals-v0.25.0`.

> **TL;DR (v0.24.0 — SOP-conformance authoring: two new skills `/evals:derive-sop` + `/evals:reconcile-sop` and a vendored v3 SOP linter. Purely additive; grader contract still v9; on-disk schema still `0.16.0`.)**
> - **Why.** The evals-platform gained an **SOP-conformance classifier**: it watches a call site's production traffic for drift from the agent's own standing procedure. Its customer-side artifact is one **v3 SOP file per call site** — seven concepts (`agent`, `intents`/`means`, `observations`, `rules` with `in`/`when`/`unless`/`at`/`expect`|`never`, optional `either`/`of`), **pure meaning, zero mechanism**. Everything mechanical (routing, detector synthesis, thresholds, bindings) compiles server-side and never enters the repo. Authoring and maintaining that file is coding-agent work on the customer's code — exactly this plugin's territory, like `instrument` before it. The schema is specified in the evals-platform repo's `classifiers/experiments/SCHEMA-V3.md` (measured basis: repo instruction text alone routes and compiles most observations; frame conventions are the gap exemplars close).
> - **New skill `/evals:derive-sop`.** Reads the call site's *whole* instruction surface (system prompts, tool definitions, routing rules — duplicated and disjoint copies included), derives demand-side intents, an atomic observation vocabulary obeying the schema's lintable style laws (atomicity, specificity, frame exemplars, atomic prohibitions), and rules mapping **every** instruction to enforced-or-deferred with reasons in comments. Exemplars are **harvested from the prompt's own clarifying asides** ("a general question does not trigger it" is a `counts_not:` exemplar, near-verbatim). Contradictions between duplicated instruction sources surface as findings for the human, never silent choices. Output: `.tessary/sops/<call_site_id>.yaml` (`::` → `/` nesting, matching the bundle's shard convention).
> - **New skill `/evals:reconcile-sop`.** The per-commit maintenance flow. The SOP stores **no provenance anchors, by design** — pointers rot, so reconciliation re-reads the whole instruction surface every time, verifies every intent/observation/rule/deferred-comment is still true, and proposes edits/retirements/additions with the verbatim evidence quoted **in the proposal only** (it never enters the file), then re-lints.
> - **Vendored linter `sop_lint.py`.** Single-file validator (Python 3 + PyYAML, nothing else — packaged like `platform.py`): seven-concept parse with collect-all validation, plus the style-law lint — atomicity **errors**, unknown intent/observation ref **errors**, exemplar-shape **errors**, hedge-word / `never:`-polarity / exemplar-count **warnings**. It extends the schema design note with the exemplar mapping form (`is:` + `counts:`/`counts_not:`, SCHEMA-V3 open item 1). Deliberately a **validator only** — no compilation, no bindings, ever. Exit codes: `0` clean, `1` errors, `2` unreadable/invalid YAML. Two fixtures ship in `sop_examples/`: `policy_gpt.yaml` (valid, exemplar form shown) and `broken.yaml` (every lint class, expected counts in its header).
> - **Docs.** README gains the flow section (connect → instrument → derive-sop → reconcile-sop, with a mermaid); `output_format.md` documents `.tessary/sops/`. **The bundle importer does not read `sops/` today** — platform upload is an ops-side seam (no tenant-facing endpoint yet), so the deliverable is the authored+linted file in the repo and the on-disk schema stays `0.16.0`. `contract/` (grader contract v9) is untouched — the SOP is not a grader artifact.
>
> **Migration.** None — purely additive: two new skills, one new script, one new (optional) bundle directory the importer ignores. Existing bundles, graders, and skills are byte-identical in behavior. If you pin the marketplace `ref`, bump it to `evals-v0.24.0`.

> **TL;DR (v0.23.1 — docs only: the hand-off no longer implies verdict bodies are authored automatically after the bundle PR merges.)**
> Imported graders are definitions with `_body_source: platform` and no body; generating the bodies from real traces is a deliberate, spend-incurring action the user triggers via **Generate** on the project's Pipeline page. README + connect/instrument skills now say so explicitly. No code, contract, or schema changes. If you pin the marketplace `ref`, bump it to `evals-v0.23.1`.

> **TL;DR (v0.23.0 — the plugin strips to its front door: `connect` + `instrument`. Local synthesis is retired; the evals-platform's observer authors and maintains the `.tessary/` bundle via draft PRs. Grader contract still v9; on-disk schema `0.15.0` → `0.16.0`.)**
> - **Why.** The platform is the source of truth for every feature that runs there, and since v8/v9 it already authored all grader verdict bodies. What remained plugin-side — discovery, definitions, validation, the viewer — ran once, locally, and went stale the moment the code changed. The platform's observer sees every push, so bundle authorship moves to the one component that can keep it true: it reads the repo on the org's schedule, authors call-site shards, code-tracked facts, failure modes, quality dimensions and grader *definitions*, and proposes them as a **draft PR**. The interactive approval loop's job is done by PR review. Bootstrap is just the degenerate case: an empty bundle means everything is new.
> - **Removed skills.** `/evals:synthesize-graders` and `/evals:regenerate-grader`. Their replacements are platform flows: the observer's scheduled authoring (bootstrap + drift remediation) and the platform's per-grader regenerate endpoint.
> - **Removed machinery.** `validate.py` + `pipeline_io.py` (the bundle validator is now **platform-owned**; it still runs — in the observer's sandbox as `tessary-evals-validate`, and in the platform's CI), `publish.py` (bundle upload is retired; bundles reach the platform by merging the observer's PR, or any push touching `.tessary/`), `viewer.py` + `viewer_template/` (the platform UI is the viewer), `dedup.py`/`audit.py`/`finalize.py` (synthesis-run machinery), `packs/` + `contract/pack.schema.json`, `authors/default/`, `prompts/`, `examples/`, `contract/PLATFORM_HANDOFF.md` (its checklist shipped).
> - **What survives.** `/evals:connect` (link, OTLP wiring, MCP tools), `/evals:instrument` (the one thing only a coding agent can do: write `tessary.call_site.id` into source), `platform.py` (now a single self-contained client: link/status/mcp-add/unlink/token/envs/coverage — it absorbed `publish.py`'s device-link, TLS and credential plumbing; the `preflight` and `fetch-traces` subcommands are gone with their only consumer), and the **contract documents** (`output_format.md`, `contract/AUTHORING_CONTRACT.md`, `contract/grader.schema.json`) — this repo remains the public home of the bundle contract, which binds the observer and human editors exactly as it bound the synthesizer.
> - **On-disk schema `0.16.0`.** No shard schema changes. The synthesis-only artifacts (`pipeline/packs.yaml`, `pipeline/priorities.yaml`, `datasets/`, `report.md`, `index.html`, `.synth-lock.yaml`, the `progress` block in `meta.yaml`) are retired — no longer produced, ignored when present. `pipeline/instrumentation.yaml` is now documented in `output_format.md` (it was always written by `/evals:instrument`).
>
> **Migration.** If you invoked `/evals:synthesize-graders` or `/evals:regenerate-grader`, stop: connect the repo on the platform (Settings → Git integration) and let the observer propose the bundle; regenerate individual graders from the platform UI. If you scripted `publish.py`, push the bundle to the repo's default branch instead — the platform imports on push. If you scripted `platform.py preflight`/`fetch-traces`, use `platform.py coverage` and the MCP `query_*`/`get_trace` tools. If you wired `validate.py` into a local pre-push hook or your own CI, remove it — there is no local validator anymore; validation now happens after push, in the platform's observer sandbox and its CI, and an invalid bundle surfaces on the import rather than blocking your commit. Existing bundles keep validating; nothing about the shard contents changed. If you pin the marketplace `ref`, bump it to `evals-v0.23.0`.

> **TL;DR (v0.22.0 — the bundle gains the CODE-TRACKED FACTS: `output_schema` + `tools` on the call-site shard, and a new `pipeline/capabilities.yaml`. Purely additive; grader contract still v9; on-disk schema `0.14.0` → `0.15.0`.)**
> - **Why.** The `.tessary/` bundle is the repo-side source of truth for what tracks the product's CODE, and the platform reads it back on every push. Three declarations had nowhere to live, so the only way to learn them was to infer them from traffic or to capture them as a side effect of grader synthesis.
> - **`output_schema` on the call-site shard.** The structured output the code declares, verbatim as a JSON Schema. The platform's Malformed Output classifier validates each observation against it. Until now its only writer was platform-side synthesis stamping the column directly — which necessarily lands *after* the traffic it describes. A declaration in the bundle **wins** over the platform's own capture, so editing this file is how you correct a stale one.
> - **`tools` on the call-site shard.** The tool schemas the call site declares to the model — `name` (required), `description`, `input_schema`, `source`. Only `name` is required on purpose: a tool we can name but not fully specify is still worth declaring, and requiring more would silently drop exactly the tools we understand least.
> - **`pipeline/capabilities.yaml`.** The product-level inventory of tools, skills, MCP servers and subagents. Everything the platform knows about an agent's capabilities today is inferred from traffic — its behaviour-drift detector learns a normal action skeleton from the project's own traces, so "a new capability appeared" and "an established step disappeared" are statistical inferences. Against a declared inventory they become a **diff**: code-but-never-traced is dead, traced-but-not-declared is genuinely unexpected. Those are indistinguishable today.
> - **Absent is not empty.** A shard silent on `output_schema` means "does not carry the fact" and the platform keeps what it captured; an explicit `output_schema: null` asserts the code declares none. `validate.py` accepts both and never treats a null as an error.
> - **Nothing writes these yet.** They are inert until the platform's observer regenerates the bundle on push. A bundle that omits all three parses exactly as before.
>
> **Migration.** None required — every existing bundle validates unchanged and the grader contract is untouched. Consumers reading `.tessary/pipeline/call_sites/*.yaml` may see two new optional keys, and a new optional `pipeline/capabilities.yaml` may appear; both are additive. Requires an evals-platform that parses schema `0.15.0` (platform PR "carry the code-tracked facts in the .tessary bundle") — an older platform ignores the new keys rather than failing, since its bundle parser drops unknown YAML fields by design. If you pin the marketplace `ref`, bump it to `evals-v0.22.0`.

> **TL;DR (v0.21.0 — the `--skip-trace-grounding` escape hatch is removed; `publish.py upload` stamps git provenance. BREAKING only for anyone passing the removed flag. Grader contract still v9; on-disk schema still `0.14.0`.)**
> - **`--skip-trace-grounding` is gone.** Grounding is now truly unconditional: a run against an environment with no tagged telemetry stops at the A.0 gate (exit `3` → `/evals:instrument`), with no ungrounded mode behind a flag. `_meta.grounding` keeps its audit-trail role with `observed` as the only value a synthesis can stamp; `regenerate-grader` still carries the existing value forward verbatim. `validate.py` no longer accepts `grounding: none`.
> - **`publish.py upload` stamps git provenance.** The uploaded copy of `pipeline/meta.yaml` (payload only — the on-disk bundle is untouched) gains top-level `commit_sha` and `repo.owner`/`repo.name` read from the repo containing the bundle, so the platform's import binds the pipeline to the commit it was synthesized against (its version-by-commit-SHA lineage; imports previously always logged `commit=null`). Outside a git repo the keys are simply absent, as before.
> - **Copy fixes.** The device-link prompt names the actual platform host instead of a hardcoded `evals.tessary.ai`; the post-publish message points at the project's Pipeline page (prompts/code are generated platform-side) instead of the stale "connect traces" setup step; `platform.py status` routes an empty pipeline through `/evals:instrument` before `/evals:synthesize-graders`.
>
> **Migration.** Drop `--skip-trace-grounding` from any scripted invocation — there is no replacement; instrument the call sites instead. Bundles carrying `_meta.grounding: none` no longer validate: re-synthesize those graders against real traces. If you pin the marketplace `ref`, bump it to `evals-v0.21.0`.

> **TL;DR (v0.20.0 — trace grounding is mandatory; new `/evals:instrument` skill; `--traces` removed. BREAKING for anyone scripting `synthesize-graders`. Grader contract still v9; `_meta.grounding` added additively; on-disk schema still `0.14.0`.)**
> - **Grader generation is now grounded in real traces, or it does not run.** `synthesize-graders` opens with a hard gate (Phase A.0): require a link → list environments with their real tagged-span counts → the user picks one explicitly (**no default**; non-interactive runs must pass `--env`) → `preflight` → `coverage`. Previously traces were an *optional local file* and the skill's own step A.0 said they "never change the graders produced" — so in practice every grader was authored from source code alone.
> - **Generation is limited to call sites the traces actually exercised.** Phase A.1 intersects static discovery with the platform's per-call-site coverage, on an exact `call_site_id` match. Sites discovered in code but never observed are **dropped**, not quietly graded, and land in a new `.tessary/pipeline/skipped_sites.yaml` with `reason: not_instrumented | no_observed_traces`. A site observed with no code home is kept and authored from trace evidence (`code_grounded: false`).
> - **New `/evals:instrument` skill.** Call-site resolution is the explicit `tessary.call_site.id` span attribute and nothing else — no filepath, span-name, or prompt-shape inference — so the ids have to be written into the code on purpose. `instrument` discovers the repo's LLM call sites, assigns each a stable id, edits the source to stamp the attribute (diff shown, confirmation required), and records `.tessary/pipeline/instrumentation.yaml`. Re-runs never rename an existing id: it is a foreign key held by every grader and every already-ingested span.
> - **Call site IDs are no longer derived.** The old rule ("Path B → snake_cased source label; Path A → `sha::<16-hex>` over the normalized system prompt") is gone. The id *is* the tag value, verbatim. An id the platform has never seen names a call site that cannot be graded.
> - **`--traces` is removed.** The platform is the only trace source; ingestion is OTLP. New `platform.py` subcommands do the reading: `envs`, `preflight`, `coverage`, `fetch-traces`. Their exit codes are contract — `0` ok, `1` not linked, `2` network, `3` linked-but-no-tagged-telemetry, `4` unknown env. `3` is distinct from `1` on purpose: "nothing to ground on" routes to `/evals:instrument`, "not connected" routes to `/evals:connect`.
> - **Escape hatch.** `--skip-trace-grounding` waives the *trace* requirement, never the link. It prints a blocking warning and stamps `_meta.grounding: none` on every grader, so a reviewer can tell at a glance which graders were never measured against production. `regenerate-grader` carries the value forward verbatim and may neither forge `observed` nor downgrade to `none`.
> - **Fetched traces are never truncated.** `fetch-traces` bounds by the **count** of traces (`--limit`), never by clipping a span's `input`/`output`/`messages`. They land in `.tessary/.cache/`, which is excluded from `publish.py upload` (it previously `rglob`'d everything under `.tessary/`) and must be gitignored.
> - **Bug fix — `stamp_meta` was destroying body-lifecycle provenance.** It rebuilt `_meta` from scratch while preserving only `locked_fields`/`human_edited`, silently dropping v9's `materialized_at` and `body_digest`. Since `body_digest` is how a human edit of a platform-materialized body is detected, every re-stamp disarmed that detection. Both keys are now preserved.
>
> **Migration.** Replace `--traces path.jsonl` with `--env <slug>`; the file is no longer read. A run against a linked project with no tagged telemetry now stops at exit `3` instead of silently producing source-inferred graders — run `/evals:instrument`, exercise the app, and re-run. Consumers reading `.tessary/graders/*.yaml` may see a new optional `_meta.grounding` (`observed` | `none`); it is additive and every existing bundle still validates. Two new pipeline files may appear: `instrumentation.yaml` and `skipped_sites.yaml`. Requires an evals-platform with `call_site_id`/`environment_id` on the query API's `observations` dataset and a `callSite` param on the traces list. If you pin the marketplace `ref`, bump it to `evals-v0.20.0`.

> **TL;DR (v0.19.1 — `/evals:connect` security + UX hardening; no contract/schema change, grader contract still v9, on-disk schema still `0.14.0`)**
> - **Security.** `platform.py token` is now gated behind `--reveal` (+ a stderr warning) so an agent step or a prompt-injection in a linked repo can't print the live project token into the transcript. The `mcp-add` fallback no longer tells the user to hand-assemble `claude mcp add … Bearer <token>` (which leaked the token into shell history) — the masked preview is non-runnable and registration always goes through `--run`, which reads the token internally.
> - **New `platform.py unlink`** — one command tears down *both* credential copies together (the repo-local `claude mcp` registration and the stored credential); prints a reminder to revoke the server-side token in the platform UI.
> - **Robustness.** `mcp-add --run` **pre-checks `claude` is on PATH before removing** any existing registration, so a PATH problem (shim/devcontainer) can never leave a repo with zero tools. The device-link poll loop survives a transient network blip instead of aborting the whole flow. `status` reports a pipeline-read `401` as "re-link", not a misleading "no pipeline yet".
> - **UX.** Dropped the "add a call site" suggestion (no create tool exists — it was a first-turn dead end); the hand-off now leads *and* closes with "reconnect to load the tools"; added a "no project yet" branch + a greenfield fork in the README; OTel detection is framed as a heuristic to confirm before proposing a `.env` edit; empty-project `status` prints a real empty-state line.
> - **Nothing else changed.** No contract/schema/authoring change — grader contract stays **v9**, on-disk schema `0.14.0`, `synthesize-graders` output byte-identical. Consumers/runners need do nothing.
>
> **Migration:** none. Patch release over v0.19.0 — `/evals:connect` behavior/copy fixes only. If you pin the marketplace `ref`, bump it to `evals-v0.19.1`.

> **TL;DR (v0.19.0 — new `/evals:connect` front door + OTLP-only ingestion; no contract/schema change, grader contract still v9, on-disk schema still `0.14.0`)**
> - **New skill `/evals:connect`** — repositions the plugin as a platform-integration assistant. It device-links a repo to an evals.tessary.ai project, detects the repo's existing OpenTelemetry and wires its **OTLP export** at the project (standard `OTEL_EXPORTER_OTLP_*` env vars → `POST /v1/traces`, token kept out of version control), registers the platform's authenticated **MCP server** into Claude Code (`--scope local`, private per-repo) so the coding agent gets native tools (`list_call_sites`, `query_*`, `run_triage`, …), and reports project status. New thin client `platform.py` (`link` / `status` / `mcp-add` / `token`) reuses `publish.py`'s device-link / TLS / credentials plumbing.
> - **OTLP-only ingestion.** The one supported way traces reach a project is **OpenTelemetry over OTLP** (`POST /v1/traces`). The plugin installs/requires **no SDK** — it points an *existing* OTel exporter at the project. Removed the dead `/traces/upload` dataset-upload from `publish.py` (that endpoint no longer exists) and scrubbed all SDK references from the connect skill, README, and the synthesize SKILL.
> - **`synthesize-graders` / `regenerate-grader` are unchanged** in behavior and kept as the **greenfield bootstrap**; the README + `plugin.json` reframe the plugin around connect. **No contract or schema change** — grader contract stays **v9**, on-disk schema stays `0.14.0`, and `synthesize-graders` output is byte-identical. Consumers/runners need do nothing.
>
> **Migration:** none. Additive plugin release — the `/evals:connect` front door alongside the existing skills. If you pin the marketplace `ref`, bump it to `evals-v0.19.0` to pick up `/evals:connect`.

> **TL;DR (v0.18.0 — new `regenerate-grader` skill; no contract/schema change, grader contract still v9, on-disk schema still `0.14.0`)**
> - **New skill `/evals:regenerate-grader <grader_id|call_site_id|chain_id> …`** — re-authors a *named set* of already-existing graders in place against the current `.tessary/` shards, and nothing else. It is the targeted-regeneration path (`synthesize-graders --only`) lifted into its own lean, **fully non-interactive** skill (no discovery/triage/dedup/taxonomy, no per-site approval gates), for when a code change made specific graders stale and you already know which ones. It never hypothesizes new failure modes/sites, never edits a `pipeline/` shard, never touches a grader you did not name, and respects `_meta.locked_fields` / `_meta.human_edited` and frozen materialized bodies. Validation and `.synth-lock.yaml` updates go through the existing `validate.py` / `finalize.py` helpers — no hand-authored locks or digests.
> - **Why it exists.** Unattended drift-remediation (the evals-platform observer) and humans refreshing a few graders previously had to invoke the full `synthesize-graders` orchestration (the multi-thousand-line phased pipeline, with mandatory human-approval stops) just to rewrite one or two graders. `regenerate-grader` is the cheap, gate-free path for that.
> - **Nothing else changed.** The grader contract (**v9**), on-disk schema (`0.14.0`), `synthesize-graders`, and `validate.py` are byte-identical to v0.17.0. Existing bundles validate unchanged. Consumers/runners need do nothing.
>
> **Migration:** none. Additive plugin release — a second skill alongside `synthesize-graders`. If you pin the marketplace `ref`, bump it to `evals-v0.18.0` to pick up `/evals:regenerate-grader`.

> **TL;DR (v0.17.0 — nested `.tessary/` shard filenames; on-disk schema → `0.14.0`; grader contract unchanged at v9)**
> - **Shard filenames now nest as folders (`::` → `/`) instead of flattening to `__`.** A Path-A call site `sha::a1b2` is written to `pipeline/call_sites/sha/a1b2.yaml`; the same `::` → `/` nesting applies to `pipeline/failure_modes/`, `pipeline/quality_dimensions/`, and graders. Purely an on-disk **filename layout** change — every shard's *contents*, the grader contract (still **v9**), and the canonical `::`-delimited `id:` **inside** each file are all unchanged.
> - **Grader files drop the redundant trailing `::grader`.** A grader `persona::memory_citation::grader` moves from `graders/persona__memory_citation__grader.yaml` to `graders/persona/memory_citation.yaml`. The id inside the file is still `persona::memory_citation::grader`.
> - **Consumers that globbed these dirs flat must recurse.** Any code doing `graders/*.yaml` / `call_sites/*.yaml` (non-recursive) or deriving an id from a filename `.stem` must switch to a recursive walk (`rglob`) and read the id from the file body (or reconstruct it from the path under the dir). All bundled plugin scripts (`pipeline_io.py`, `finalize.py`, `viewer.py`, `validate.py`, `dedup.py`) were updated; the `.synth-lock.yaml` `graders:` map is now keyed by the grader's path under `graders/` (minus `.yaml`) rather than its bare stem.
> - **Platform import needs no change.** `BundleAssembler.classify()` already routes by substring (`contains("graders/")`, `contains("pipeline/call_sites/")`) and reads the canonical `id` from inside each file, so it accepts the deeper tree as-is; the directory-upload and recursive git-tree auto-pull paths enumerate nested files already. The contract is vendored into evals-platform via its `scripts/sync-evals-contract.sh` — **re-sync required** to pick up the doc/`pipeline_io.py` parity edits.
>
> **Migration:** **hard cutover, no migration.** The new plugin only writes the nested layout. Existing flat `.tessary/` folders are **not** rewritten in place — re-run `/evals:synthesize-graders` (or delete `.tessary/` first) to regenerate under the new layout. Bundle *contents* and the grader contract are unchanged, so a regenerated bundle imports exactly as before.

> **TL;DR (v0.16.0 — previous — grader contract v9: grader-body lifecycle (deferred → materialized → human-edited), OTel-grounded `expected_spans.source`, three redundant grader fields removed; on-disk schema → `0.13.0`)**
> - **Grader body lifecycle — `_body_source` is now a three-state enum `{platform, platform-materialized, human}`.** v8's single `platform` value (DEFERRED — empty body, expanded by the platform on import) is joined by two materialized states. After the platform expands a deferred body it **materializes it back into the repo** (the repo is the source of truth): its GitHub integration opens a PR that writes the inline `judge_prompt`(+`rubric`), sets `_body_source: platform-materialized`, and stamps `_meta.materialized_at`, `_meta.body_digest`, and `_meta.locked_fields: [judge_prompt(, rubric)]`. For `platform-materialized`/`human` the inline body MUST now be **present** (the inverse of `platform`, which requires it empty); the body is **frozen** — synthesis/re-runs never re-author or overwrite it. A human editing a materialized body changes its content so the recomputed `body_digest` no longer matches `_meta.body_digest`; the file is promoted to `_body_source: human` + `_meta.human_edited: true`, and that human revision becomes the new authoritative body that syncs back upstream on the next platform sync. The plugin ships **no client/pull tooling** — the platform's GitHub integration is the only writer of the materialize-back PR; the plugin defines/validates the on-disk states and freezes the body. Body-digest canonicalization: SHA-256 over the normalized body (per-line trailing whitespace stripped, leading/trailing blank lines stripped; `llm_judge` = `judge_prompt` + `"\n"` + `rubric`).
> - **`expected_spans` gains `source: observed | inferred`** (call-site shard; default `inferred` when absent — every v8 entry is unchanged). `observed` = the span name was read from real OTel/trace telemetry (a verified fact); `inferred` = the v8 best-effort static-code guess. The platform should **trust `observed` entries unconditionally** and rank by `confidence` only among `inferred` (`confidence` is optional/moot on observed entries). **Path A (traces provided) now emits `source: observed` entries from the real span names** (it previously dropped them), and a new **optional, skippable Phase-A micro-step A.0** solicits OTel/trace data (OTLP `traces.jsonl`, a flat span-name list, or a Langfuse/Phoenix/Jaeger export) before discovery — a **no-op in unattended/subagent runs**, degrading gracefully to today's Path-B inference. A verified `observed` entry supersedes any inferred guess for the same site.
> - **Three redundant grader fields removed.** `owner`, `cost_budget_tokens`, `latency_budget_ms_p95`, and the **grader-level** `compliance_tags` are dropped from the grader schema; the long-dead `applies_when_check` (feature-removed in v6) is fully dropped from the contract (`validate.py` no longer special-cases it). The **failure-mode-level** `compliance_tags` is unchanged and still consumed. None were author-emitted; all were unread or write-only on the grader. **Platform team — please confirm the importer does not consume the grader-level `owner`/cost/latency budgets or grader-level `compliance_tags`** (the in-repo audit found only the FM-level `compliance_tags` is read); flagged in `PLATFORM_HANDOFF.md`, not blocking.
> - **Grader contract v8 → v9; on-disk schema `0.12.0` → `0.13.0`.** `grader.schema.json` `$id` → `grader.v9.json` (widened `_body_source` enum, optional `_meta.materialized_at`/`body_digest`, removed-field property deletions, slimmed `_meta.locked_fields` enum); the `llm_judge`/`score` `allOf` conditionals are reused as-is — widening the enum makes them correctly require the present body for the materialized/human states. `validate.py` widens `VALID_BODY_SOURCES`, adds the present-body branch in `_check_body_source`, adds `_canonical_body_digest`/`_check_body_digest` (human-edit detection), adds `VALID_EXPECTED_SPAN_SOURCE` + the `source`-aware confidence rule in `_bundle_expected_spans`, and drops the `applies_when_check` error branch (and the now-dead `_normalize_applies_when`). `output_format.md`/`PLATFORM_HANDOFF.md`/`AUTHORING_CONTRACT.md`/`SKILL.md`/`per_site_kit.md`/the viewer/`finalize.py` default are updated. The contract is vendored into evals-platform via its `scripts/sync-evals-contract.sh` — re-sync required.
> - **Author conformance — v9 is fully author-transparent.** Its changes impose no new author obligation (the removed fields were orchestrator-owned/never-emitted; the materialized/human body states are produced by the platform sync-back, never by an author; `expected_spans.source` is orchestrator-owned). So the **contract** version is `9` but a conformant author keeps declaring `_meta.author_contract_version: 8` — there is no `9` author obligation and the orchestrator requires `9` of no author. The bundled default author and the `--author-contract-version` default stay `8`.
>
> **Migration:** existing v8 bundles validate **unchanged** — all v9 fields are optional/defaulted (absent `source` ⇒ inferred; absent `_body_source` ⇒ legacy inline; the three removed fields are simply ignored if a legacy file still carries them, the schema has no `additionalProperties` constraint). No v8 grader has the new present-body-with-marker shape, so none is newly rejected. Platform/runner — handle the two new `_body_source` import branches (use materialized/human bodies as-is; treat `human` as authoritative); raise the materialize-back PR from your GitHub integration; trust `expected_spans.source: observed` entries; confirm the removed grader-level fields are unused. Plain TEXT/JSON throughout — **no DB migration**.

> **TL;DR (v0.15.0 — previous — grader contract v8: judge-prompt authoring deferred to the platform for `llm_judge`/`score`; new `expected_spans` call-site field)**
> - **Judge body moves to the platform for `kind=llm_judge` / `kind=score`.** The plugin no longer authors `judge_prompt`/`rubric`. For these two kinds the grader now carries a top-level marker **`_body_source: platform`** plus only the *definition* — `kind`, `applies_when` (llm_judge), `rubric_levels`+`score_scale` (score), `confidence`, `rationale`. The platform synthesizes the runtime verdict body (the injection-hardened judge system prompt, the in-scope `applies_when` gate, the JSON verdict contract, the pass/fail rubric or the integer-scoring instruction) on import. `kind=deterministic`/`execution`/`agentic` bodies are **unchanged** — still plugin-authored inline. This keeps the judge-prompt craft in one place rather than re-authored per grader by every plugin author.
> - **`_body_source` marker semantics.** Its only legal value is `platform`; it is valid **only** on `llm_judge`/`score`. When present, the grader **must not** carry a non-empty `judge_prompt`/`rubric` (`validate.py` flags an author that set the marker but forgot to drop the body). When **absent**, the v7 requirements still hold — `llm_judge` needs inline `judge_prompt`+`rubric`, `score` needs inline `judge_prompt` — so deterministic/execution/agentic graders and any legacy inline-body files keep validating unchanged. Net: the requirement *relaxes only when the marker is present*, so v8 is additive/backward-compatible at the schema level.
> - **New call-site field `expected_spans` (schema → `0.12.0`).** The discovery step that locates each call site in code now also extracts the telemetry nomenclature the instrumentation emits — OTel `start_span("…")`/`start_as_current_span`, Langfuse `name=`/`@observe(name=)`/`update_current_observation(name=)`, logger/tracer names, the enclosing function name (SDK default span name), provider-SDK default naming — and records it on the call-site shard as a best-effort list of `{match_field (name|model|trace_id|metadata.<key>), match_pattern (exact or glob), kind (span|trace), confidence (high|medium|low)}` so the platform can bind a grader to the right captured spans/traces. Orchestrator-owned (authors do not write it); omitted/empty when no hint is found; never a hard requirement.
> - **Grader contract v7 → v8.** `grader.schema.json` `$id` is now `grader.v8.json` (adds the `_body_source` enum property; relaxes the `llm_judge`/`score` `allOf` conditionals so the inline body is required only when `_body_source != "platform"`). `AUTHORING_CONTRACT.md` is v8; the bundled default author and `evals-prompt` declare `8`. `validate.py` gains `_check_body_source` (marker enum + kind-scope + "did you actually defer" body-empty checks), skips the inline-body non-empty checks for platform-deferred graders in `_check_kind_body`/`_check_score_body`, and adds `_bundle_expected_spans` (per-entry field/enum validation). On-disk schema → `0.12.0`. `PLATFORM_HANDOFF.md` documents the platform's import-time body expansion and the `expected_spans` consumption.
> - **Author conformance.** v8 is author-transparent for deterministic/execution/agentic, but NOT for `llm_judge`/`score`: a `< 8` author still hand-writes `judge_prompt`/`rubric` (which still validates as the legacy inline shape, but is not the platform-deferred shape v8 wants), so the orchestrator requires `8` whenever it wants a platform-deferred body. New authors declare `8` and defer the body for `llm_judge`/`score`.
>
> **Migration:** authors — for `llm_judge`/`score`, stop emitting `judge_prompt`/`rubric` and emit `_body_source: platform` + the definition; deterministic/execution/agentic authoring is unchanged. Platform/runner — on grader import, when `_body_source == "platform"` and the body is empty, synthesize the judge prompt/rubric from the linked failure-mode / quality-dimension definition; otherwise keep using the inline body. Consumers reading call-site shards — optionally read `expected_spans` for span binding; it is plain TEXT/JSON, no migration required. Legacy inline-body grader files keep validating with no changes.

> **TL;DR (v0.14.0 — previous — grader contract v7: per-grader `self_tests` removed; behavior calibrated platform-side against golden datasets)**
> - **`self_tests` are gone from the grader file.** Graders no longer carry hand-authored sample outputs + expected verdicts. A grader's behavior is now validated **platform-side against golden datasets**: in evals-platform a curator marks a (static) dataset of real captured spans as `golden`, associates it with a grader, and labels each item with the expected verdict/level per `(grader, dataset_item)`; the platform runner then scores the grader against those real spans. This replaces the per-grader test suite that was duplicated, buried in each grader's YAML, and hard to grow.
> - **`self_test_pass_rate` / `self_test_variance` are removed.** There is no more step-6 self-test calibration in the plugin; calibration signal comes from golden-dataset runs on the platform. The synthesis subagent now only authors + validates graders — it no longer reruns self-tests forward/reversed to compute pass-rate and variance.
> - **The `applies_when ↔ not_applicable` self-test invariant is removed.** Out-of-scope items are surfaced as `not_applicable` by the platform runner at golden-run time, not asserted by the author. `applies_when` itself is unchanged (still author-owned, still always LLM-evaluated; the `applies_when_check` removal from v6 still stands).
> - **Grader contract v6 → v7.** `grader.schema.json` `$id` is now `grader.v7.json` (`self_tests` property, `$defs/self_test`, the two calibration fields, the `self_tests` lockable field, and the `applies_when ↔ not_applicable` invariant all removed; `self_tests` dropped from `required`). `AUTHORING_CONTRACT.md` is v7; the bundled default author and `evals-prompt` declare `7`. `validate.py` drops all self-test checks (`_check_self_tests`, `_check_self_test_body`, `_check_score_self_tests`, `_check_adversarial_coverage`, `_check_verdict_consistency`, `_check_pass_fail_balance`, the self-test branch of `_check_pipeline_refs`, the pass-rate check, and the `VALID_SELF_TEST_CATEGORIES` / `ADVERSARIAL_REQUIRED_AT_SELF_TESTS_COUNT` constants).
> - **Author-transparent in the safe direction.** v7 only *removes* fields, so a stray `self_tests` block from an older author / hand-edited file is simply ignored (the platform's grader ingest tolerates unknown keys). New authors declare `7` and emit no self-tests.
>
> **Migration:** authors — stop emitting `self_tests`, `self_test_pass_rate`, `self_test_variance`. Consumers/runners — read calibration from golden-dataset runs, not from grader-file self-tests; a grader file no longer carries inline test cases. Curation/UI — calibrate a grader by associating a golden dataset and labeling its items per grader (see evals-platform). No action needed for files that already lack self-tests.

> **TL;DR (v0.13.0 — previous — grader contract v6: applies_when is always an LLM gate; applies_when_check removed)**
> - **`applies_when` is now always evaluated by an LLM at runtime.** For `kind=llm_judge`/`score` it rides inline in the judge prompt (unchanged). For `kind=deterministic` the runner now runs a **separate LLM applicability gate** before the body and skips out-of-scope outputs — so the `deterministic_check` is **gate-free** (implement only the failure check; never decide applicability). The compiled deterministic gate is gone.
> - **`applies_when_check` is removed.** It was the code-evaluable mirror compiled into a deterministic body; authors must no longer emit it and `validate.py` flags it. The JSON schema retains the key as `deprecated` so legacy files still parse.
> - **Trace deterministic checks receive structured input.** A `scope: trace` deterministic check runs against `input.messages` (`{role, content, tool_uses: [{name, input}]}`) and `input.tool_uses` (flattened), not a flattened transcript. Trace self-test turns may carry a structured **`tool_uses`** array so the check validates against the same shape it grades on.
> - **Grader contract v5 → v6.** `grader.schema.json` `$id` is now `grader.v6.json`; `AUTHORING_CONTRACT.md` is v6; the bundled default author and `evals-prompt` declare `6`. v6 is NOT author-transparent for **deterministic graders with a gate** (a `< 6` author emits the removed field / bakes the gate in); other grader shapes are unaffected.
>
> **Migration:** authors — drop `applies_when_check` and keep `deterministic_check` gate-free; for trace deterministic graders, reason over `input.messages`/`input.tool_uses` and put each turn's tool calls in the self-test `tool_uses` field. Runners — for a `kind=deterministic` grader with a non-empty `applies_when`, run an LLM applicability gate before the (gate-free) body (evals-platform already does as of the corresponding release).

> **TL;DR (v0.12.2 — previous — publish survives a missing/foreign CA store)**
> - **`publish.py` now resolves a CA bundle instead of trusting urllib's default.** Every request in the publish flow went out on urllib's default TLS context, which trusts only OpenSSL's compiled-in CA paths. On the python.org macOS build those paths are empty until you run `Install Certificates.command`, so `link` (and therefore `publish` / `--publish`) died on the consuming machine with `[SSL: CERTIFICATE_VERIFY_FAILED] unable to get local issuer certificate` before reaching the gate. The request helper now builds the context from, most-specific first: an explicit `SSL_CERT_FILE` / `REQUESTS_CA_BUNDLE` (for a TLS-intercepting corporate proxy), else the system store when it already trusts CAs (Linux/Homebrew), else the `certifi` bundle if importable (rescues the bare python.org macOS build). Injected once in the request helper, so all four endpoints inherit it.
> - **Verification is never disabled, and the diagnostic is now actionable.** A genuinely unverifiable host still fails loudly (nothing is sent over an untrusted connection); the error message now names the two real fixes — run `Install Certificates.command` / `pip install --upgrade certifi`, or set `SSL_CERT_FILE` to the corporate root — instead of a bare `URLError`.
> - **No contract, schema, grader, or on-disk-output change.** Schema stays `0.11.0`, grader contract stays v5. Client-only fix in the plugin's network layer, stacked on the v0.12.1 User-Agent fix; the link cache stays at `~/.config/tessary-evals/credentials.json`. `certifi` is an optional fallback — its absence is fine on any host whose system store already has CAs.
>
> **Migration:** none. Re-run `publish` (or `/evals:synthesize-graders --publish`) — a link handshake that previously failed TLS verification on a python.org macOS Python now succeeds.

> **TL;DR (v0.12.1 — previous — publish upload works behind Cloudflare)**
> - **`publish.py` now sends a real `User-Agent`.** Every request in the publish flow — the device-code `link` start/poll, the grader `import`, and `traces/upload` — went out with urllib's default `Python-urllib/X.Y` agent, which the `evals.tessary.ai` Cloudflare WAF blocks with a `403` (a custom rule matching that exact token; `curl`/`requests`/empty UAs pass). So `publish` / `--publish` and the silent per-site re-uploads were failing for every user behind the edge since the flow shipped in v0.10. Requests now carry `User-Agent: tessary-evals/<plugin-version> (+https://evals.tessary.ai)` plus a stable, version-free `X-Tessary-Client: evals-cli` marker, injected once in `publish.py`'s request helper so all four endpoints inherit them.
> - **No contract, schema, grader, or on-disk-output change.** Schema stays `0.11.0`, grader contract stays v5. This is a client-only fix in the plugin's network layer; consumers reading `.tessary/**` and grader authors are unaffected, and the link cache stays at `~/.config/tessary-evals/credentials.json`. The `X-Tessary-Client` header is forward-looking only — no Cloudflare change is required for the fix (the new UA alone returns `200`); it's a stable marker for any future WAF allow-rule, deliberately version-free so such a rule never needs per-release maintenance.
>
> **Migration:** none. Re-run `publish` (or `/evals:synthesize-graders --publish`) — uploads that previously `403`'d now succeed.

> **TL;DR (v0.12.0 — breaking output-directory rename)**
> - **Output directory renamed from `tessary-evals/` to `.tessary/`.** A single dotted top-level directory keeps the synthesis output out of the way (hidden by default, one entry instead of two). Consumers must update any path that reads from `tessary-evals/pipeline/`, `tessary-evals/graders/`, `tessary-evals/datasets/`, `tessary-evals/index.html`, `tessary-evals/report.md`, or `tessary-evals/.synth-lock.yaml` — replace the leading `tessary-evals/` segment with `.tessary/`. Validator and viewer flags shift accordingly: `validate.py --bundle .tessary/`, `viewer.py .tessary`.
> - **User pack override directory folded in as `.tessary/packs/`.** What was the repo-root `.tessary-evals-packs/` is now the `packs/` subdirectory of the bundle (`<repo>/.tessary/packs/<id>/`). Same discovery semantics; one fewer top-level directory.
> - **No schema, contract, or grader-file shape changes.** Pure rename — schema stays `0.11.0`, grader contract stays v5. Re-run synthesis once to migrate, or `mv tessary-evals .tessary && mkdir -p .tessary/packs && mv .tessary-evals-packs/* .tessary/packs/` if you'd rather not regenerate. The publish link cache stays at `~/.config/tessary-evals/credentials.json` (unchanged).
>
> **Migration:** in CI, replace any path beginning `tessary-evals/` with `.tessary/`. In repos with custom packs, move the override directory under `.tessary/packs/`. That's it.

> **TL;DR (v0.11 — previous — multi-turn auto-detection + pinned trace sourcing)**
> - **New call-site field `default_grade_mode`** (`per_turn | per_conversation`; schema → `0.11.0`, default `per_turn`). Discovery now **detects multi-turn sites structurally** — ≥ 2 spans sharing a `trace_id`/session for a `conversational_turn`/`agent_step` site (Path A) or a session with ≥ 2 turns (Path A-agent) — and marks them `per_conversation`. That flag is what tells the orchestrator to author the site's cross-turn graders as `scope: trace` (previously the decision lived only in a failure author's prose). Absent is treated as `per_turn`, so pre-0.11 pipelines and consumers are unaffected.
> - **Trace-grader history sourcing is pinned to the final turn's self-contained `input`.** In practice (Langfuse / Claude-Code-style instrumentation) each turn's logged `input` already carries the whole prior transcript, so the runner grades a multi-turn site by grouping observations by trace, taking the **latest turn**, and judging its transcript-bearing `input` + final output — no per-turn stitching. This **shrinks the platform's `scope: trace` work**: the multi-message `RawEntry` and agent-session `SourceFactory` from the v0.10 handoff are no longer required for trace `llm_judge` graders (still relevant for `kind: agentic`). The author-facing self-test shape (`input_messages` + `final_output`) is unchanged.
> - **Contract → v5** (`AUTHORING_CONTRACT.md`, `output_format.md`, `PLATFORM_HANDOFF.md`, `validate.py`), schema → `0.11.0`. All additive and **author-transparent**: a v4 author stays fully conformant (no author change). `validate.py --bundle` rejects an unknown `default_grade_mode`. See [`contract/PLATFORM_HANDOFF.md`](contract/PLATFORM_HANDOFF.md) for the (reduced) runner work.

> **TL;DR (v0.10 — previous — publish to evals.tessary.ai)**
> - **New opt-in publish flow.** `publish.py` connects a synthesis run to a project on evals.tessary.ai via a device-code handshake (like `gh auth login`), then pushes the bundle to the existing import endpoint with a project-scoped token. Offered once at the site-1 gate (reply `publish`) or pre-consented with `--publish`. The only network egress in the skill; never runs without consent.
> - **Captured datasets become the first verdicts.** After importing graders, `publish.py upload` sends `datasets/*.jsonl` to the platform's new trace-upload endpoint, which grades them immediately (the call site is derived from each dataset's filename). The user then lands on the project's **Connect traces** step to wire up more traces. No on-disk output shape changed.
> - **Link is persisted** at `~/.config/tessary-evals/credentials.json` (keyed by repo), so later sites re-upsert silently and future runs reuse the token (re-linking only on a 401).

> **TL;DR (v0.10 — previous — multi-turn traces, agent-session ingestion, agentic graders)**
> - **New `scope: trace` (multi-turn graders).** Until now graders were `single_call` (one output) or `chain` (across N *distinct* call sites). Neither could grade a conversation's *final* turn given the prior turns. `scope: trace` does exactly that — input = the first n-1 messages, graded artifact = the final turn — anchored to one `call_site_id` like `single_call`. Self-tests carry `input_messages` + `final_output` instead of `sample_output`. Used for cross-turn coherence failures on `conversational_turn` / `agent_step` sites.
> - **Agent-session ingestion.** A new discovery sub-path ("Path A-agent") reconstructs Claude Code / opencode session JSONL (turns with `tool_use`/`tool_result` blocks, file edits) into `datasets/<id>.jsonl` using a new **agent-session row shape**: a `messages` array plus an optional per-turn `repo_state: {commit, git_diff}` so the **git diff between two turns** is captured as text and becomes a gradeable artifact.
> - **New `kind: agentic`.** A failure-mode grader whose binary verdict is produced by an **agent running in a sandbox** (via opencode) — running `git diff`, tests, or file exploration — rather than a single judge call. The author emits an `agent_spec` (harness, sandbox image + network, allowed_tools, task_prompt, verdict_contract, budgets); **the plugin only emits the spec — the runner (evals-platform) executes it.** For failures that can only be judged by inspecting the result the agent produced (did the repo end up correct?), typically on `sandbox_agent` / `cli_agent` sites.
> - **Contract → v4** (`grader.schema.json`, `AUTHORING_CONTRACT.md`, `authors/default`), schema → `0.10.0`. All additive: existing grader shapes unchanged; `scope`/`kind` enums simply gain values. Viewer marks trace graders with a `multi-turn (trace)` chip, renders the conversation in trace self-tests, and shows the `agent_spec` for agentic graders. **The runner-side work (multi-turn input, agent-session source, opencode sandbox executor) is documented for the platform in [`contract/PLATFORM_HANDOFF.md`](contract/PLATFORM_HANDOFF.md) — this release ships the contract, not the runner.**

> **TL;DR (v0.9 — previous, indirect LLM call discovery)**
> - **Discovery now sees more than in-process SDK calls.** Until now, call-site discovery looked only for in-process provider/framework SDK calls (`messages.create`, `chat.completions.create`, LangChain/LangGraph objects, etc.). Any model reached *out of process* was invisible: shelling out to an agent CLI (`claude`, `opencode`, `aider`, `codex`, `ollama`), raw HTTP to a model endpoint or gateway with no SDK, or an agent run inside a sandbox (e2b/modal/daytona/docker). v0.9 broadens Path B (static) discovery to all four classes and tags each site so these stay in visibility.
> - **New call-site field `invocation`** (`sdk | cli_agent | http | sandbox_agent`; schema → `0.9.0`). Absent is treated as `sdk`, so pre-0.9.0 pipelines and consumers are unaffected. Path A (traces) sets it from span attributes when the model was reached via a CLI/HTTP/sandbox path, else `sdk`.
> - **Indirect sites get the right failure surface.** These calls usually have **no in-repo system prompt** (it lives in the external tool) and **no enforced output schema**, so the per-site hypothesis pivots: output-contract drift on free-text stdout, tool/model version non-determinism, agent-loop runaway and cost/latency blowups, untrusted-output trust (output written/executed/chained downstream), sandbox escape, and argument/prompt injection into the spawn. They're often the highest-severity calls precisely because they run un-prompted and un-schema'd.
> - **Viewer** marks indirect calls with an `Agent CLI` / `Raw HTTP` / `Sandbox` chip on the call-site row and modal; SDK calls stay unmarked. `validate.py --bundle` rejects an unknown `invocation` value. No change to grader-file shapes — consumers reading `tessary-evals/**` see the same files plus the new call-site field.

> **TL;DR (v0.8 — quality-dimension scoring)**
> - **New eval family: quality dimensions.** Until now every grader was binary (`pass | fail`), so the synthesizer could only ever produce "did X go wrong" checks — it never generated graders that *assess how good the output is*. v0.8 adds **quality dimensions**: continuous 1–5 scores on an anchored rubric, judged by a new **`kind: score`** LLM-judge, tracked as a trend over time (never a gate). These are the grey-area evals — "given valid inputs, did it make the *best* choice and does the reasoning hold up?" — that binary checks structurally miss.
> - **New shard `pipeline/quality_dimensions/<call_site_id>.yaml`** (schema → `0.8.0`). The per-site hypothesis step now produces 2–5 quality dimensions for any *judgment* call site (`agent_step`, `route`, `rag_answer`, `classify`, `draft`, `rerank`, `summarize`, `conversational_turn`, or anything attaching a justification/basis/citation). Mechanical sites (`embedding`, strict-schema `extract`, pure `guardrail`/`moderation`) are exempt.
> - **Never skipped, never deferred.** `validate.py --bundle` makes a judgment-shape call site with zero quality dimensions a hard error — in full *and* `--partial` mode — so this category can't silently go missing again. Quality dimensions are graded in the first sweep for the site being processed (scoped to that one site, not a project-wide sweep) and are never subject to the failure-mode deferral cut.
> - **Grader contract → v3 (additive).** `kind: score` graders carry `quality_dimension_id`, `score_scale`, `rubric_levels`, and score self-tests (`expected_level`, categories `clear_high`/`clear_low`/`near_miss`/`adversarial`). v2 authors remain fully conformant for failure-mode graders; only `kind: score` needs v3. The bundled `authors/default` author and the schema, validator, viewer, and report all handle the new kind. No change to existing failure-mode grader shapes.

> **TL;DR (v0.7 — previous, phased synthesis with deferred failures)**
> - **Phased synthesis is now the only mode.** Discovery + triage run once up front, then the orchestrator processes one call site at a time, rebuilds the viewer after each, and shows the user the working HTML before the rest of the repo is processed. Time-to-first-artifact drops from "after every site is graded" to "after site 1 is graded" — usually 2–3 minutes on a fresh run.
> - **First-sweep cutoff: `severity: high`.** During the per-site loop, only high-severity failures become graders. Medium- and low-severity failures are still hypothesized and written into `pipeline/failure_modes/*.yaml`, but each one carries `grader_deferred: true` and no grader file. Consumers reading the bundle keep getting the same `failure_modes` shape; deferred entries just have `grader_id: null`.
> - **Adaptive approval gate.** The orchestrator pauses for the user after sites 1 and 2 (strict gate while time-to-first-value matters), then measures per-site wall time from those two and proposes a batch sized to fit roughly ten minutes for the next group. `--pause-every N` overrides if the user wants a fixed cadence. The gate is a hard turn-ending stop — the orchestrator yields control to the user and does not continue the priority list unattended (v0.7.1 strengthened this after a run blew past the gate and consumed a whole session).
> - **`/evals:synthesize-graders --complete <call_site_id>`** flips that site's deferred failures to non-deferred and synthesizes their graders. `--complete all` does the same for every site in priority order under the same adaptive gate. When the last deferred failure clears, `finalize.py` runs in non-partial mode and `validate.py --bundle` is the authoritative gate again.
> - **New CLI surface on the bundled scripts:** `validate.py --partial` (skips FM↔grader bijection for deferred FMs, downgrades coverage gates to warnings), `audit.py --partial` (counts failure modes against coverage, suppresses pack-contribution and generic-name checks, never exits non-zero), `finalize.py --partial` (threads through to validate, writes `sites_completed` / `sites_total` / `deferred_failure_count` into `pipeline/meta.yaml`).
> - **Viewer** now renders deferred failure modes with a "deferred" badge and shows progress (e.g. `5/12 complete · 48 deferred`). It no longer hard-exits when `pipeline/` is sparse — partial states render cleanly.
> - **Removed:** the old linear step-numbered DAG (0 → 8) and any batch-mode flow. Existing consumers reading `tessary-evals/**` see the same on-disk shape; only the orchestration changes.
>
> - **v0.7.2 — discovery granularity, severity calibration, viewer titles.** Path B (static, no-trace) discovery now splits a single physical call location into one call site per branch when the prompt or response schema is selected from a registry / map / enum / dispatch keyed on a parameter (e.g. a `_run_gate(gate_name)` that does `load_prompt(gate.system_prompt_path)` and `schema = gate.response_schema`), and especially when the trace `use_case` is parameterized per branch — so per-gate / per-kind operations get graded separately instead of being merged into one wordy call site. Severity calibration in `per_site_kit.md` is tightened: `high` is now reserved for trust/safety/correctness breakers and capped at ~⅓ of a site's failures, so the first-sweep grader set is genuinely the critical slice (a v0.7.0 run was marking ~49% of failures `high`).
> - **v0.7.4 — version bookkeeping.** The on-disk pipeline schema version (the `version` field in `meta.yaml`, distinct from the plugin version) is bumped `0.4.0 → 0.7.0` to reflect the shard-schema changes phased synthesis introduced — the `progress` block in `meta.yaml`, the `priorities.yaml` shard, and the `grader_deferred` field on failure modes — all now documented in `output_format.md`. The viewer footer no longer shows the misleading internal "v3" label; it shows the on-disk schema version (or just the skill name when absent).
> - **v0.7.3 — factual call-site names.** Call sites are titled by their `use_case` (the v0.7.2 id-as-title / use_case-as-subtitle viewer change is reverted). The fix moves to the source: discovery now writes `use_case` as a short factual noun phrase (≈3–6 words) naming what the call produces — dropping transport/implementation descriptors (`stream`, `async`, `cached`, `structured`, `via cron`), rationale tails (`… to reduce token usage`), and input plumbing. E.g. "Stream conversational chat responses about completed test sessions" becomes `Answer questions about a test session`.
>
> **Migration:** new runs of `/evals:synthesize-graders` default to the phased flow. Existing `tessary-evals/` directories from a prior version resume cleanly via the SHA-verified lock — every shard already on disk is treated as "completed" if the lock entry matches. If you want exhaustive grader coverage as before, run `/evals:synthesize-graders --complete all` after the first sweep finishes.

> **TL;DR (v0.6 — previous, SHA-verified resumable runs + leaner subagent fan-out)**
> - **Resume from a prior run, deterministically.** The lock file at `tessary-evals/.synth-lock.yaml` now records, per step, the exact set of files that step produced and the SHA-256 of each file's content. A step is skipped only when every recorded file is still present with a matching hash — file existence alone is never enough, so an interrupted run, a half-edited shard, or a tree copied in from elsewhere never gets mistaken for a completed step. Step 6 (grader synthesis) resumes per failure mode via a per-file SHA check, so a partially-emitted batch continues from the first missing grader. `--force` overrides every skip.
> - **Two new CLI helpers** exposed by `pipeline_io.py`: `check-step <N>` (exit 0 if step is complete and verified), `lock <N> <paths>...` (record outputs under a step). The lock schema gains a `completed_steps:` map alongside the existing `shards:` / `graders:` SHA buckets; `finalize.py` preserves it on its end-of-run refresh.
> - **Per-call-site subagents now read one instruction file.** What used to be three separate prompts (`classify_shape.md`, `extract_intent.md`, `hypothesize_failures.md`) is consolidated into a single `prompts/per_site_kit.md`. Fan-out batches issue all Agent calls back-to-back so the identical instruction prefix is reused across subagents in the batch, lowering per-site token cost on large repos.
> - **No schema, contract, or grader-file shape changes.** Consumers that read `tessary-evals/**` see the same files. Authors of bundled packs see the same `pack.yaml` / `interview.md` / `failures.md` contract.

> **TL;DR (v0.5 — previous, breaking output-directory rename)**
> - **Output directory renamed from `evals/` to `tessary-evals/`.** Avoids collision with directories named `evals/` already present in many repos. Consumers must update any path that reads from `evals/pipeline/`, `evals/graders/`, `evals/datasets/`, `evals/index.html`, `evals/report.md`, or `evals/.synth-lock.yaml` — replace the leading `evals/` segment with `tessary-evals/`. Validator and viewer flags shift accordingly: `validate.py --bundle tessary-evals/`, `viewer.py tessary-evals`.
> - **User pack override directory renamed from `.evals-packs/` to `.tessary-evals-packs/`.** Same rationale.
> - **No schema, contract, or grader-file shape changes.** Pure rename — re-run synthesis once to migrate, or `mv evals tessary-evals && mv .evals-packs .tessary-evals-packs` if you'd rather not regenerate.
>
> **Migration:** in CI, replace any path beginning `evals/` with `tessary-evals/`. In repos with custom packs, rename the override directory. That's it.

> **TL;DR (v0.4 — previous, breaking layout change)**
> - **Pipeline split into shards.** What was `tessary-evals/pipeline.yaml` is now `tessary-evals/pipeline/{meta,packs,product_profile,invariants,chains,taxonomy}.yaml` plus per-site files under `tessary-evals/pipeline/call_sites/` and `tessary-evals/pipeline/failure_modes/`. Consumers that read the monolithic file must migrate: use the bundled `pipeline_io.load_pipeline(evals_dir)` helper for a v0.3-compatible assembled mapping, or read shards directly.
> - **Orchestrator architecture rewritten for large repos.** Steps 0 and 1 now run as parallel subagents; steps 2+3+4 are folded into a per-call-site subagent fan-out (batches of 30); steps 4.6, 4.7, 7 are deterministic Python scripts. The main agent holds only small return manifests — never call-site bodies, failure descriptions, taxonomy details, or grader bodies. Repos with 30–50+ call sites now synthesize cleanly without main-context exhaustion or token-budget overflow on the final write.
> - **New bundled scripts** under the plugin root: `dedup.py` (step 4.6), `audit.py` (step 4.7), `finalize.py` (step 7 — writes meta.yaml, report.md, .synth-lock.yaml, runs the bundle validator), `pipeline_io.py` (shared shard reader/writer).
> - **`validate.py --bundle`** now walks `tessary-evals/pipeline/**` shards (via `pipeline_io.load_pipeline`) instead of a single `pipeline.yaml`. Per-file mode's `--pipeline` flag now accepts a `tessary-evals/` directory in addition to a legacy `pipeline.yaml` file.
> - **`viewer.py`** consumes the sharded layout via the same loader; the HTML template (`viewer_template/`) is unchanged.
> - **`.synth-lock.yaml`** now records SHA-256 of every shard *and* every grader. Shard divergences are informational (shards under `pipeline/` are orchestrator-owned); grader divergences still trigger the v0.3 `human_edited` / `locked_fields` flow.
> - **No grader-contract change.** `contract/AUTHORING_CONTRACT.md`, `contract/grader.schema.json`, `contract/pack.schema.json`, and the bundled packs are unchanged. Grader files on disk are unchanged.
>
> **Migration note for consumers:** if you read `tessary-evals/pipeline.yaml` today, switch to one of:
> ```python
> import sys; sys.path.insert(0, "<plugin>")
> import pipeline_io
> pipeline = pipeline_io.load_pipeline(Path("tessary-evals"))  # v0.3-compatible mapping
> ```
> or read shards directly per `output_format.md`.

> **TL;DR (v0.3 — previous)**
> - **Pipeline schema bumped from v0.2.0 → v0.3.0.** New top-level `packs[]` block; new `failure_modes[].pack_ids` and `failure_modes[].compliance_tags` (set-valued tags, not part of identity); grader files mirror these tags. New `pack.schema.json` defines manifests.
> - **Four bundled packs ship by default**: `quality` (always-on, free), `security` (addon — covers all governance/regulatory/PII), `reliability` (included — anchored to observed.* stats), `brand` (addon).
> - **New step 0.5 — pack discovery + pre-filled interview.** Interview questions are answered automatically from step-0 product analysis where possible; the user is only asked when no signal exists in the repo.
> - **New step 4.6 — dedup & merge across packs**, with deterministic three-pass merge (exact / semantic / conflict-suffix). The audit was renumbered to step 4.7.
> - **`validate.py`** gains `--pack <id>` filter (coverage matrix + compliance-tag report), `_bundle_pack_resolution`, `_bundle_dedup_uniqueness`, `_bundle_pack_dependencies` checks.
>
> **v0.2 (previous TL;DR — still applies)**
> - **Pipeline schema bumped from v0.0.1 → v0.2.0.** New top-level `runtime` block; new `call_sites[].observed`, `call_sites[].source_spans`, `call_sites[].dataset_path`; new `failure_modes[].layer` value `C`; extended `call_sites[].shape` enum; extended `chains[].detection_method` enum.
> - **Grader contract bumped from v1 → v2.** New author-owned fields: `self_tests[].category`, `applies_when_check`. New orchestrator-owned fields: `self_test_variance`, `_meta` (provenance + locks), operational fields (`owner`, `block_on_fail`, `cost_budget_tokens`, `latency_budget_ms_p95`, `dataset_refs`).
> - **OTel ingestion** uses standard `gen_ai.*` semconv only — no Langfuse-specific attributes.
> - **`validate.py`** gains `--bundle <dir>` mode for global checks (FM↔grader bijection, chain DAG acyclicity, taxonomy reachability, layer-A/B/C coverage gates, lock-file consistency).
> - **New files on disk:** `tessary-evals/datasets/<call_site_id>.jsonl` (captured inputs) and `tessary-evals/.synth-lock.yaml` (re-run safety).
> - **Targeted regeneration** via `/evals:synthesize-graders --only <id>` for fixing one grader after curator review.

---

## Commit-by-commit history

### `92a4802` — Initial commit (contract v1)

Established the orchestrator pipeline (steps 0–8), the grader-author contract v1, the per-file validator, and a Langfuse-flavored trace ingestion path.

Files: `SKILL.md`, `prompts/`, `output_format.md`, `contract/AUTHORING_CONTRACT.md`, `contract/grader.schema.json`, `validate.py`, `authors/default/AUTHOR.md`.

### `18f66be` — Viewer, license, coherence/SOLID pass

Added `viewer.py` + `viewer_template/` (HTML viewer for the synthesized bundle), `LICENSE`, plus refactors for orthogonality across prompts and SKILL.md. Validator refactored into small per-rule predicates so adding/removing rules stays local.

### `fa57758` — Viewer redesign

Re-themed the viewer as a light-theme inspector with tables + modals. No schema changes.

### Uncommitted changes (this PR — v0.2)

The bulk of the changes documented below. Schema v0.0.1 → v0.2.0, contract v1 → v2.

---

## What's new in v0.3 (packs + dedup + interview pre-fill)

### v0.3.1 — Four high-level packs

`synthesize-graders` now ships four bundled concern-bundle "packs" at `packs/<id>/`:

| Pack | Tier hint | When it's on by default | What it contributes |
|---|---|---|---|
| `quality` | free | always | Layer A/B baseline — faithfulness, helpfulness, calibration, audience-fit, schema/format |
| `security` | addon | `regulatory_context` non-empty, or `data_sensitivity` non-empty, or user-supplied content reaches the prompt | Layer C adversarial robustness + regulatory compliance failures, narrowed by the interview to apply only to regulations / data classes / threat surfaces that exist |
| `reliability` | included | traces ingested with `observed.*` stats | Layer C cost regressions, latency regressions, output variance, fallback hygiene; budgets anchored to observed p95 |
| `brand` | addon | `brand_voice_signals` non-empty, or user-facing call sites exist | Layer A banned-term checks + Layer B tone / voice / persona consistency / competitor handling |

Pack identity lives in the **set-valued** `failure_modes[].pack_ids: [string, ...]` — a failure can belong to multiple packs (e.g. `ai_disclosure_omitted` is both `brand` and `security`). Compliance control mappings travel as `failure_modes[].compliance_tags: [string, ...]`.

**Failure IDs are pack-agnostic**: `<call_site_id>::<failure_name>`. Toggling a pack does not rename or duplicate failures. The only exception is the rare "conflict suffix" case at step 4.6 dedup where two packs propose the same name with materially different rubrics — see § v0.3.3.

`tier_hint` on each pack manifest is **informational only** — the orchestrator and validator never enforce it. Your consuming product reads `pipeline.packs[].tier_hint` and gates enablement in your UI / API.

### v0.3.2 — Step 0.5: pack discovery + pre-filled interview

A new step runs between product analysis (step 0) and call-site discovery (step 1). It does three things:

1. **Discovery**. Loads bundled packs from `$PLUGIN/packs/` and user packs from `$REPO/.tessary-evals-packs/`. Each pack's `applies_when.auto_signals` is matched against step-0 artifacts and (after step 1) the call sites. Packs are categorized as *always-on / auto-recommended / opt-in / explicit*.

2. **Pre-filled interview**. Each pack's `interview.md` declares per-question pre-fill rules pointing at step-0 artifacts (e.g. *Q1.regulations pre-fills from `product_profile.regulatory_context`*). The orchestrator:
   - Resolves answers from `product_profile`, `implicit_invariants`, `invariant_coverage`, dependency lists, and observed trace stats whenever possible.
   - Asks the user **only the questions no signal answered**.
   - Records each answer with `source: product_profile | invariants | code | observed | dependency | user` and `evidence: <file path>`.
   - Prints a transparency line per question so the user can see what was inferred vs asked.

3. **Manifest hygiene**. Validates pack manifests against `contract/pack.schema.json`; checks `dependencies` are satisfied and `conflicts` aren't co-engaged.

**What consumers need to do**: read `pipeline.packs[].interview_answers` if you want to surface the resolved Q&A in your UI. Each pack records `content_digest` so re-runs can detect when a pack itself changed (separately from product / trace changes).

### v0.3.3 — Step 4.6: deterministic dedup & merge across packs

The numbered pipeline became `0 → 0.5 → 1 → 2 → 3 → 4 → 4.5 → 4.6 → 4.7 → 5 → 6 → 7 → 8`. The previous step 4.6 audit moved to step 4.7.

The new step 4.6 is a **single deterministic pass** that takes the union of baseline + each pack's contributions + chain failures and produces one canonical `failure_modes:` list. Three passes in order:

1. **Exact merge** — failures sharing `(scope, call_site_id|chain_id, name)` collapse. `pack_ids` and `compliance_tags` union; `severity` takes the max; `layer` takes the most specific (C > B > A); `description` takes the longest non-empty contributor.
2. **Semantic merge** — pairs within the same `(scope, site_or_chain, layer)` group whose names differ only by trivial morphology, or whose descriptions are near-duplicates, merge under the lexicographically smaller name.
3. **Conflict suffix** — failures that survive both passes but still share a name with materially different descriptions disambiguate by appending the second contributor's pack id: `<name>__<pack_id>`. The orchestrator prints a `WARN`. This is the only case pack identity enters a failure name; pack authors are advised to namespace within their pack to avoid it.

Determinism guarantee: given the same step-0/1/4/4.5 inputs and the same engaged packs at the same `content_digest`s, step 4.6 produces byte-identical output across re-runs. The canonical sort (by `scope` → `site_or_chain` → `name`) + lexicographic tiebreakers are the load-bearing pieces.

**Output line**:

```
Step 4.6: dedup — 93 raw failures → 76 canonical (12 exact-merged, 5 semantic-merged, 0 conflict-suffixed); packs contributing: [quality, security, reliability, brand]
```

### v0.3.4 — Step 4.7: audit (renumbered, with pack-aware checks added)

Previously step 4.6. Now reads the **post-dedup** canonical list and adds three new questions to the existing audit:

- Did every engaged pack contribute at least one failure? (A dead pack record is usually an interview problem.)
- Did dedup produce conflict-suffixed names? (Surface for pack maintainer to namespace.)
- Do all `failure_modes[].pack_ids` resolve to a `pipeline.packs[].id`? (A non-resolvable pack id is a step-4.6 bug.)

`validate.py --bundle` enforces all of these deterministically; the audit prompt is the soft check.

### v0.3.5 — `validate.py` extensions

- `_bundle_pack_resolution` — every `pack_ids` entry on a failure mode or grader resolves to `pipeline.packs[].id`.
- `_bundle_dedup_uniqueness` — no two failures share `(scope, call_site|chain, name)` post-dedup. Failure to merge in step 4.6 is now a hard error rather than a silent duplicate.
- `_bundle_pack_dependencies` — declared `dependencies` are also engaged; `conflicts` are not co-engaged.
- `--pack <id>` — narrows the bundle output to one pack and prints a compliance-tag coverage matrix:

  ```
  --pack security: coverage matrix
    failures: 14 ({'A': 1, 'B': 3, 'C': 10})
    graders: 14
    compliance tags:
      EU-AI-Act.Art-13: 4
      EU-AI-Act.Art-15: 6
      HIPAA-164.502: 2
      HIPAA-164.514: 2
      NIST-AI-RMF.MS-2.6: 4
  ```

  Useful for compliance reviewers who want to see one regulation's footprint without reading 90 grader files.

### v0.3 migration checklist

For consumers upgrading from v0.2 → v0.3:

- [ ] **Schema version**: `pipeline.yaml.version` is now `"0.3.0"`. Bump consumer pins.
- [ ] **New top-level `packs[]`**: render in your viewer if you want users to see which packs are active and the interview Q&A. Safe to ignore otherwise.
- [ ] **`failure_modes[].pack_ids` and `compliance_tags`**: render as tag chips. Both are sets; a failure can carry multiple.
- [ ] **Same on graders**: `pack_ids` and `compliance_tags` are mirrored from the failure mode onto the on-disk grader. Already-built filters in your viewer should accept both.
- [ ] **Conflict-suffixed names**: `<name>__<pack_id>` is a legal failure name (rare; only after a pack conflict). Render normally; the pack tags carry the differentiation.
- [ ] **`--pack` filter**: useful for compliance review surfaces. Wire it into your CI / GRC export if you have one.

## What's new in v0.2 (and what you need to do)

### 1. OTel trace ingestion uses standard `gen_ai.*` semconv only

**What changed.** The Path A (traces-provided) ingestion in `SKILL.md` step 1 no longer reads Langfuse-specific attributes (`langfuse.observation.input`) or the non-standard `llm.use_case`. It now exclusively follows the [OTel GenAI semantic conventions](https://opentelemetry.io/docs/specs/semconv/gen-ai/):

- Provider/model: `gen_ai.system`, `gen_ai.request.model`, `gen_ai.response.model`.
- Operation: `gen_ai.operation.name` (`chat | text_completion | generate_content | embeddings | execute_tool`).
- Messages in: `gen_ai.input.messages` (newer semconv) or span events `gen_ai.{system,user,assistant,tool}.message`.
- Messages out: `gen_ai.output.messages` or `gen_ai.choice` events.
- Token / cost: `gen_ai.usage.input_tokens`, `gen_ai.usage.output_tokens`.
- Status / refusal: `status.status_code`, `gen_ai.response.finish_reasons`.

A reference JSONL is in `examples/sample_traces.jsonl`. Both OTLP/JSON and the flatter Python SDK exporter shape are accepted; the orchestrator normalizes them internally.

**What you need to do.** If you produce traces for synthesize-graders, make sure your instrumentation emits `gen_ai.*` attributes — most OTel-native and OpenLLMetry/OpenInference SDKs already do. Vendor-only attributes (`langfuse.*`, `langsmith.*`) are ignored.

### 2. Extended span taxonomy in Path A

**What changed.** The ingestion now distinguishes seven span flavors:

| Flavor | Detection | Treatment |
|---|---|---|
| chat / completion | `gen_ai.operation.name ∈ {chat, text_completion, generate_content}` | Primary call site source. |
| tool / function | `gen_ai.operation.name == "execute_tool"` or `gen_ai.tool.name` set | Own call site with `shape: tool_call`. |
| embedding | `gen_ai.operation.name == "embeddings"` | `shape: embedding`; observability-only. |
| streaming | `gen_ai.choice` events with empty `gen_ai.output.messages` | Output reconstructed from event deltas. |
| errored | `status.status_code == ERROR` or refusal/content_filter finish reason | Feeds `observed.error_rate` / `observed.refusal_rate`; high-signal for Layer B/C. |
| retry duplicate | identical normalized prompt within ≤100ms in same `trace_id` | Collapsed; not double-counted. |
| non-GenAI | no `gen_ai.*` | Ignored. |

**What you need to do.** Nothing if you only consume `pipeline.yaml`. If you run your own ingestion on the same traces, mirror this taxonomy to avoid double-counting retries or misclassifying tool spans as `chat`.

### 3. Sampling, time windowing, and observability stats

**What changed.** Each call site in `pipeline.yaml` now carries an `observed` block populated from the trace window:

```yaml
call_sites:
  - id: ...
    observed:
      first_seen: <iso8601>
      last_seen: <iso8601>
      error_rate: <float>
      refusal_rate: <float>
      p50_latency_ms / p95_latency_ms: <int>
      p50_tokens_in / p95_tokens_in / p95_tokens_out: <int>
      cost_estimate_usd: <float>
      redaction_state: none | partial | redacted | unknown
```

Representative samples for `source_spans` and dataset capture are now **stratified across 5 time buckets** (up to 2 per bucket, cap 10 total). The orchestrator also warns when one call site dominates the trace (> 70% of retained spans) and asks before ingesting traces older than 90 days.

**What you need to do.** Curation tools / runners can now prioritize graders by `observed.error_rate` or budget judge invocations against `observed.p95_*`. If you previously inferred latency/cost from your own telemetry, you can read it from `pipeline.yaml` directly.

### 4. Redaction-aware ingestion

**What changed.** Spans whose content is composed entirely of placeholder tokens (`<REDACTED>`, `[PII]`, `***`, runs of 40+ identical hex chars, masked emails) are no longer hashed into fake call sites. They group under a single explicit `sha::redacted` call site and trigger a warning asking for an unredacted replay sample. Partially redacted spans get `call_sites[].observed.redaction_state: partial` so downstream Layer C "PII leakage" graders can treat the placeholder pattern as the canonical leak shape.

**What you need to do.** If your trace store applies redaction in the pipeline before exporting to JSONL, expect to see a `runtime.redaction_state: redacted` warning and a thin call-site catalog — re-ingest with content capture enabled or accept the synthetic call site.

### 5. New chain detection: `parent_id` trees + ensembles

**What changed in `prompts/analyze_chains.md`.**

- The chain detector now builds a tree per `trace_id` from `parent_id`. Sequential parent → child edges are the primary detection signal.
- Sibling spans with **identical normalized prompts under the same parent** are detected as `detection_method: ensemble` (self-consistency, voting, n>1 sampling) rather than a sequential chain. Combined shape is `ensemble_vote`. Chain emits `ensemble_span_ids: [<hex>, ...]`.
- Two new chain failure categories: **ensemble disagreement masked** (chosen output overrides majority) and **ensemble majority wrong** (consistent agreement on a wrong answer).
- Modelling parallel siblings as a sequential chain is now an explicit anti-pattern.

**What you need to do.** Runner code that fetches outputs for chain graders must support ensemble shape: `chain.call_site_ids` for an ensemble is the same id repeated, and the runner uses `ensemble_span_ids` to fetch N sibling outputs from the trace store.

### 6. New shape enum values

**What changed in `prompts/classify_shape.md` and `pipeline.yaml.call_sites[].shape`.** Added: `embedding`, `rerank`, `guardrail`, `moderation`, `ensemble_vote`. Disambiguation rules made explicit (e.g. "a numeric-vector output is `embedding`, not `extract`").

**What you need to do.** If your viewer or grader runner switches on `shape`, add cases for the new values. Embedding sites won't have judge graders — only observability/cost/latency entries.

### 7. New failure-mode layer: **Layer C — adversarial / operational**

**What changed in `prompts/hypothesize_failures.md`.** Per call site, the orchestrator now produces 11–26 failure modes across **three** layers:

- Layer A: 3–8 mechanical / structural (unchanged).
- Layer B: 5–12 user-centric / judgmental (unchanged).
- **Layer C (new): 3–6 adversarial / operational** — prompt injection, jailbreak, PII/secret leakage, tool-arg exfiltration, cost regression, latency regression, non-determinism, audit-trail loss.

Per-shape priorities table was extended with a Layer C column. Step 4.6 audit now enforces Layer C coverage; step 5 taxonomy gains a `Layer C flavor` cluster (`prompt_injection_resistance`, `pii_leakage`, `secret_leakage`, `tool_arg_exfiltration`, `cost_regressions`, `latency_regressions`, `output_variance`, `audit_trail_loss`).

`failure_modes[].layer` now accepts `A | B | C | null` (chain failures use `null`).

**What you need to do.** Update consumers that group failures by layer (your viewer probably does). The Layer C category is the one a security reviewer will look for first; its graders are typically the highest-severity / `block_on_fail: true`.

### 8. Grader contract v2 — new author-owned fields

**What changed in `contract/AUTHORING_CONTRACT.md` and `contract/grader.schema.json`.**

- **`self_tests[].category`** — required taxonomy of self-test purpose: `clear_pass | clear_fail | near_miss | adversarial | not_applicable`. When `self_tests` has ≥ 4 entries, at least one must be `adversarial` (a sample_output that attempts to manipulate the judge — prompt-injection, role-confusion, fake prior verdict). `validate.py` enforces.
- **`applies_when_check`** — code-evaluable mirror of `applies_when`, required when `kind=deterministic` AND `applies_when` is non-empty. The judge evaluates `applies_when` natural-language; deterministic graders need a predicate a developer can implement as a function.

The default author (`authors/default/AUTHOR.md`) now wraps judge-prompt outputs in nonce-fenced markers (`<<BEGIN_OUTPUT_<nonce>>>` / `<<END_OUTPUT_<nonce>>>`) and instructs the judge to treat the contents as untrusted data — closing the prompt-injection vector on the judge itself.

**What you need to do.**

- If you have a custom grader author skill, declare `contract_version: 2` and emit `self_tests[].category` + `applies_when_check`. v1 authors keep working but will fail validation on the new gates.
- If you write graders by hand, add `category` to each self-test and add at least one adversarial test once you have 4+ tests.
- If your judge runtime executes the rubric, parse the new nonce-fenced output blocks. The runtime mapping (`applicable=false → not_applicable`; `applicable=true,passed=true → pass`; else `fail`) is unchanged.

### 9. New orchestrator-owned grader fields

**What changed in `contract/grader.schema.json` and `output_format.md`.**

| Field | Type | Source |
|---|---|---|
| `self_test_variance` | `float \| null` | Flip rate across order-permuted reruns (step 6 calibration). Position-bias signal. |
| `owner` | `string \| null` | Free-text owner / team handle. |
| `block_on_fail` | `bool \| null` | Override `runtime.severity_policy`. |
| `cost_budget_tokens` | `int \| null` | Soft cap per judge invocation. Defaulted from `observed.p95_tokens_in/out`. |
| `latency_budget_ms_p95` | `int \| null` | Soft cap. Defaulted from `observed.p95_latency_ms × 2`. |
| `dataset_refs` | `[ref, ...] \| null` | Pointers to real inputs — `{trace_id, span_id}`, `{file: "path:line"}`, or `{jsonl_path}`. |
| `_meta` | object | Provenance + lock metadata (see § 11). |

**What you need to do.** Runners can read `dataset_refs` to replay graders against real inputs without re-fetching from the trace store. CI integrations can enforce `block_on_fail` (or fall back to `runtime.severity_policy`). All new fields are optional — v1 consumers will keep working if they ignore them.

### 10. New top-level `runtime` block in `pipeline.yaml`

```yaml
runtime:
  judge_model: <string | null>
  judge_temperature: <float>            # default 0.0
  max_concurrency: <int>                # default 8
  budget_usd_per_run: <float | null>
  severity_policy:
    high: block | warn | report         # default: block
    medium: block | warn | report       # default: warn
    low: block | warn | report          # default: report
  redaction_state: none | partial | redacted | unknown
```

**What you need to do.** Runners and CI integrations should read `runtime.severity_policy` to map severity → gate behavior. Previously you had to guess; now there's a default and a place to override.

### 11. Survivable re-runs: `_meta` block + `.synth-lock.yaml`

**What changed.** Every grader file emitted by step 6 now carries a `_meta` block:

```yaml
_meta:
  author: default | evals-prompt | ...
  author_contract_version: 2
  synthesized_at: <iso8601>
  synth_inputs_digest: <hex>            # SHA-256 over canonical author input
  locked_fields: [judge_prompt, rubric, ...]
  human_edited: <bool>
```

The orchestrator also writes `tessary-evals/.synth-lock.yaml` (one SHA-256 per grader file) at the end of every run. On re-run, before doing anything, the orchestrator:

1. Loads the lock file and compares each grader's current hash against it.
2. Reads `_meta.locked_fields` and `_meta.human_edited` on every existing grader.
3. If any divergence or hand-edit is detected, asks the user (or accepts `--force`).
4. Passes `existing_grader.locked_fields` to the author; the author preserves listed fields **verbatim** (rejected by `validate.py` if mutated).

**What you need to do.**

- Curators: set `_meta.locked_fields: [judge_prompt, rubric]` (or similar) on any grader you hand-edit. Future runs will preserve those fields.
- Alternatively, set `_meta.human_edited: true` to skip re-synthesis of that file entirely (orchestrator passes it through untouched).
- CI: don't strip `_meta`. It is the survivability contract.

### 12. New `validate.py --bundle` mode

```bash
python3 validate.py --bundle tessary-evals/
python3 validate.py --bundle tessary-evals/ --calibration-set human_labels.csv
```

In addition to running every per-file check, bundle mode enforces:

- **FM↔grader bijection** — every `failure_modes[].grader_id` has a file, every file is referenced.
- **Chain DAG acyclicity** — internal cycles (`[A,B,A]` outside ensembles) and cross-chain cycles.
- **Taxonomy reachability** — every `taxonomy_node_id` resolves; no orphan nodes without children or failure modes.
- **Duplicate ID detection** across files.
- **Layer-A/B/C coverage gates** from step 4.6, deterministically (no longer just LLM-time soft check).
- **Lock consistency** — flags grader files that diverge from `.synth-lock.yaml` without `_meta` justification.
- **Optional calibration set** — informational agreement report against a CSV of human verdicts (`grader_id, sample_output, verdict`).

Per-file mode (`python3 validate.py <file>.yaml [--pipeline …]`) is unchanged and still works.

**What you need to do.** CI: replace `for f in tessary-evals/graders/*.yaml; do validate.py $f --pipeline ...; done` with a single `validate.py --bundle tessary-evals/`. The output is the same shape (exit non-zero on any error, errors on stderr) but catches an entire class of cross-file bugs that per-file mode can't see.

### 13. Captured-input datasets

**What changed.** Path A ingestion writes `tessary-evals/datasets/<call_site_id>.jsonl` containing up to 10 stratified representative spans per call site:

```jsonl
{"trace_id": "...", "span_id": "...", "parent_span_id": "...", "timestamp": "...", "input_messages": [...], "observed_output": "...", "observed_finish_reason": "...", "observed_tokens_in": N, "observed_tokens_out": M, "redaction_state": "none"}
```

Spans with `redaction_state: redacted` are filtered out (the runner can't usefully replay them). Each grader's `dataset_refs` includes a `jsonl_path: datasets/<call_site_id>.jsonl` entry pointing at the dataset.

**What you need to do.** Eval runners can now run graders against real inputs without touching the original trace store — just replay each row.

### 14. Targeted regeneration (`--only`)

**What changed.** New invocation pattern documented in `SKILL.md`:

```
/evals:synthesize-graders --only <grader_id|call_site_id|chain_id>
```

Skips steps 0–5; spawns one subagent for the affected call site/chain with the failure-mode list filtered to the named id. The lock file is updated only for the regenerated files. Combine with `--force` to also overwrite `_meta.locked_fields`.

**What you need to do.** This is the daily workflow once the initial pipeline exists. Direct curators here when a grader needs a small fix.

### 15. Per-grader calibration: position bias + adversarial probing

**What changed in `SKILL.md` step 6.** The in-subagent calibration loop now:

- Applies the rubric to each self-test twice — once forward, once with order/iteration reversed. Reports `self_test_variance = (flips / total)`.
- For `category: adversarial` self-tests, verifies the rubric actually catches the injection. If the rubric passes an adversarial output, confidence is demoted to `low` and the manifest flags `adversarial_uncaught: true`.
- Maps to `confidence`:
  - `pass_rate ≥ 0.8 AND variance ≤ 0.1` → `high`
  - `pass_rate ≥ 0.5 AND variance ≤ 0.2` → `medium`
  - else → `low`

**What you need to do.** Trust `confidence` as a signal slightly less than before — `low` no longer just means "thin context", it can also mean "the judge flipped under permutation". Surface `self_test_variance` in your viewer when non-null.

---

## Migration checklist

For a consumer of synthesize-graders output upgrading from v0.0.1 → v0.2:

- [ ] **Schema version**: `pipeline.yaml.version` is `"0.2.0"`. Bump any consumer that pins to `0.0.1`.
- [ ] **`call_sites[].shape`**: handle new values `embedding`, `rerank`, `guardrail`, `moderation`, `ensemble_vote`.
- [ ] **`call_sites[].observed`**: optional, but populate UI / prioritization off it when present.
- [ ] **`call_sites[].source_spans` and `dataset_path`**: deep-link from grader view to traces / replay dataset.
- [ ] **`failure_modes[].layer`**: accept new value `C`. Render Layer C as its own column / section.
- [ ] **`chains[].detection_method`**: accept new value `ensemble`. Render `ensemble_span_ids` in the chain viewer.
- [ ] **`runtime` block**: read `severity_policy` for CI gating. Honor `block_on_fail` per-grader override.
- [ ] **Graders**: read `self_tests[].category` (added in v2); display `adversarial` tests distinctly. Honor `_meta.locked_fields` and `_meta.human_edited` on overwrite.
- [ ] **Datasets**: optionally replay `tessary-evals/datasets/<call_site_id>.jsonl` rows through each grader.
- [ ] **Validator**: switch CI from per-file loop to `python3 validate.py --bundle tessary-evals/`.
- [ ] **Author skills (if any)**: declare `contract_version: 2` and emit `self_tests[].category` + `applies_when_check`.
- [ ] **Judge runtimes (if any)**: parse the nonce-fenced output delimiters in the default judge prompt.

---

## Backwards compatibility

All new fields are optional. v0.0.1 consumers that ignore unknown keys continue to work — they just won't surface the new signals. The breaking changes are concentrated in:

1. The shape and detection_method enums (new values).
2. `failure_modes[].layer` accepting `C`.
3. The validator's coverage gates and adversarial requirement — graders that don't carry Layer C failures or adversarial self-tests will fail `--bundle` mode.

If you need to ship a strict consumer for v0.0.1 and v0.2 side-by-side, gate on `pipeline.yaml.version`.

---

## References

- [`SKILL.md`](SKILL.md) — orchestrator spec
- [`output_format.md`](output_format.md) — on-disk schemas
- [`contract/AUTHORING_CONTRACT.md`](contract/AUTHORING_CONTRACT.md) — grader-author contract v2
- [`contract/grader.schema.json`](contract/grader.schema.json) — machine-readable schema
- [`prompts/`](prompts/) — per-step reasoning prompts
- [`authors/default/AUTHOR.md`](authors/default/AUTHOR.md) — bundled grader author (v2)
- [`examples/sample_traces.jsonl`](examples/sample_traces.jsonl) — reference OTel GenAI trace shape
- [`packs/`](packs/) — bundled packs (`quality`, `security`, `reliability`, `brand`)
- [`contract/pack.schema.json`](contract/pack.schema.json) — pack manifest schema (v1)
- [`validate.py`](validate.py) — authoritative validator (per-file + `--bundle` + `--pack`)
