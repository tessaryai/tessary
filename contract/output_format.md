# The `.tessary/` bundle — format reference

The bundle is a directory of shards, not a single file: one shard per logical artifact, small
enough to be written and reviewed independently.

**Who writes it.** The bundle is authored and maintained by the **platform's observer**: it reads
the repo on the org's schedule, writes these shards, and proposes every change as a draft PR the
user reviews and merges. A human can edit any shard directly — the repo is the source of truth, and
the platform imports whatever is at HEAD. (`/evals:instrument` writes exactly one shard,
`pipeline/instrumentation.yaml`.) Earlier plugin versions synthesized this bundle locally; that
path is retired, and the artifacts only it produced (noted under **Retired artifacts** below) are
no longer written.

**Who reads it.** The platform's importer, on every push that touches the bundle. It assembles the
shards, mirrors them into the project (repo wins), and drops unknown YAML keys by design — which is
why the parity anchor below exists. Stick to these schemas exactly.

```
.tessary/
  pipeline/
    meta.yaml                          # version, product_hint, runtime — the one REQUIRED shard
    product_profile.yaml               # product profile
    invariants.yaml                    # implicit_invariants + invariant_coverage
    instrumentation.yaml               # call_site_id -> file/line/method (/evals:instrument)
    call_sites/<id_path>.yaml          # one per call site (`::` -> `/`)
    chains.yaml                        # all detected chains
    failure_modes/<call_site_id_path>.yaml  # single_call failures for that site
    failure_modes/_chains.yaml         # chain failures (one file for all)
    quality_dimensions/<call_site_id_path>.yaml  # 1-5 quality axes per judgment site
    taxonomy.yaml                      # full taxonomy tree
    capabilities.yaml                  # product-level tool/skill/MCP/subagent inventory
  graders/
    <call_site>/<failure>.yaml         # one per grader (`::` -> `/`, drop `::grader`)
  sops/
    <call_site_id>.yaml                # v3 SOP-conformance file (/evals:derive-sop)
```

Filenames nest the canonical `::`-delimited ID as folders (`::` → `/`). Grader
files additionally drop the redundant trailing `::grader`. Example: a grader with
`id: persona::memory_citation::grader` is written to
`.tessary/graders/persona/memory_citation.yaml`. The canonical ID *inside* the
file still uses `::`.

The same `::` → `/` nesting applies to call-site shards
(`call_sites/<id_path>.yaml`), failure-mode shards (`failure_modes/<call_site_id_path>.yaml`),
and quality-dimension shards (`quality_dimensions/<call_site_id_path>.yaml`). A call-site id is
the `tessary.call_site.id` tag value, which normally contains no `::`, so it stays a flat filename
(`support.answer` → `call_sites/support.answer.yaml`).

## `.tessary/pipeline/meta.yaml`

```yaml
version: "0.16.0"
product_hint: <string | null>

runtime:
  judge_model: <string | null>           # e.g. "claude-sonnet-4-6"; null = runner default
  judge_temperature: <float>              # default 0.0
  max_concurrency: <int>                  # default 8
  budget_usd_per_run: <float | null>      # soft cap; runner warns when exceeded
  severity_policy:
    high: <block | warn | report>         # default: block
    medium: <block | warn | report>       # default: warn
    low: <block | warn | report>          # default: report
  redaction_state: <none | partial | redacted | unknown>
```

`version` is the bundle's on-disk schema version, not the plugin version. Bump only when the shard
layout or shard schemas change. Current schema is `0.16.0`:

- 0.9.0 added the `invocation` field on call sites so indirect LLM calls — agent CLIs, raw HTTP,
  sandbox runners — are tracked alongside in-process SDK calls;
- 0.10.0 added `scope: trace` graders (grade the final turn of a multi-turn session given the prior
  n-1 messages) and the `kind: agentic` grader (binary verdict from an agent in a sandbox via
  `agent_spec`);
- 0.11.0 added the `default_grade_mode` field on call sites so multi-turn sites are flagged at
  discovery and their graders default to `scope: trace`;
- 0.12.0 added the `expected_spans` call-site field (telemetry nomenclature read from the call
  site's code, for platform span binding) and the grader `_body_source: platform` marker that
  defers `judge_prompt`/`rubric` authoring for `kind=llm_judge`/`score` to the platform —
  contract v8;
- 0.13.0 widened `_body_source` to the three-state body lifecycle
  {platform, platform-materialized, human} (the platform materializes the verdict body back into
  the repo via a GitHub PR, and a human edit promotes it to `human`), added the optional
  `_meta.materialized_at`/`body_digest`, added the `source: observed | inferred` provenance field
  on `expected_spans` entries, and removed the redundant grader fields
  `owner`/`cost_budget_tokens`/`latency_budget_ms_p95`/grader-level
  `compliance_tags`/`applies_when_check` — contract v9;
- 0.14.0 nests shard filenames as folders (`::` → `/`) instead of flattening them to `__`, and
  grader files drop the redundant trailing `::grader`;
- 0.15.0 added the CODE-TRACKED FACTS — `output_schema` and `tools` on the call-site shard, and the
  `pipeline/capabilities.yaml` inventory — the declarations that describe the product's source
  rather than its traffic. All three are optional and additive;
- 0.16.0 moved bundle authorship to the platform's observer and retired the synthesis-only
  artifacts (`packs.yaml`, `priorities.yaml`, `datasets/`, `report.md`, `index.html`,
  `.synth-lock.yaml`, the `progress` block in this shard). Shard schemas are unchanged; the grader
  contract stays v9. A bundle still carrying the retired artifacts parses — they are ignored, not
  errors.

## `.tessary/pipeline/product_profile.yaml`

```yaml
product_profile:
  domain: <string | null>
  user_types:
    - role: <string>
      surface: <string>
      constraints: <string>
  business_model: <string | null>
  data_sensitivity:
    - label: <string>
      evidence: <string>             # "<file path>: <reason>"
  regulatory_context:
    - label: <string>
      evidence: <string>
  brand_voice_signals:
    - label: <string>
      evidence: <string>
  notable_dependencies: [<string>, ...]
```

## `.tessary/pipeline/invariants.yaml`

```yaml
implicit_invariants:
  - name: <snake_case>
    description: <string>
    confidence: <high | medium | low>
    evidence:
      - <string>                     # "<file path>: <reason>"
    applies_to: <"all_call_sites" | [<call_site_id>, ...]>

invariant_coverage:
  - invariant: <name>
    enforced_in: [<call_site_id>, ...]
    likely_gap_in: [<call_site_id>, ...]
```

## `.tessary/pipeline/instrumentation.yaml`

Written by `/evals:instrument` — the durable record of which call sites are tagged in the code,
where, and how. An id in this file is **frozen**: it is a foreign key held by every grader and every
ingested span. See the instrument skill for the `state: stale | skipped` semantics.

```yaml
version: 1
call_sites:
  <call_site_id>:
    file: <string>
    line: <int>
    method: <otel_attribute | wrapped_span>
    tagged_at: <iso8601>
    state: <stale | skipped>         # only when NOT a live tag; omit for the normal case
    reason: <string>                 # optional; e.g. user_declined
```

## `.tessary/pipeline/call_sites/<id>.yaml`

One file per call site.

```yaml
id: <string>                       # the `tessary.call_site.id` span-tag value, verbatim. Never derived.
use_case: <string | null>          # human-readable display hint
invocation: <sdk | cli_agent | http | sandbox_agent>  # how the model is reached; default sdk
provider: <string>                 # "anthropic" / "openai" / "litellm" / "other"
model: <string | null>
system_prompt: <string | null>     # often null for cli_agent/sandbox_agent (prompt lives in the external tool)
shape: <summarize | extract | rag_answer | classify | draft | route | tool_call | agent_step |
        conversational_turn | embedding | rerank | guardrail | moderation | ensemble_vote | other>
shape_confidence: <high | medium | low>
default_grade_mode: <per_turn | per_conversation>  # schema 0.11.0; default per_turn.
                                   # per_conversation marks a multi-turn site (agents, chat) whose
                                   # turns share a trace and are graded once over the whole session;
                                   # its graders are then authored as scope: trace.
                                   # The platform treats this as the default; its per-call-site
                                   # curation toggle overrides it.
intent: <string>
constraints:
  - kind: <schema | length | format | refusal | citation | other>
    description: <string>
    enforcement: <deterministic | judge>
sample_count: <int>

# Where the call lives in the code (a starting hint, not a boundary)
file_hint: <string | null>
line_hint: <int | null>
surrounding_code: <string | null>  # optional; the code snippet around the call site that grounded
                                   # shape/intent/constraints and expected_spans.

# Telemetry nomenclature the call site's instrumentation emits (schema 0.12.0). OPTIONAL,
# best-effort. Two provenances (v9, `source`): INFERRED (default) from explicit instrumentation
# visible in `surrounding_code` (OTel start_span("…")/start_as_current_span, Langfuse name= /
# @observe(name=) / update_current_observation(name=), logger/tracer names, the enclosing function
# name = the SDK default span name, provider-SDK default naming); OBSERVED from the real spans
# captured for this call site — the span name is then a verified fact. Omitted / empty when no hint
# is found. The platform uses it to bind a grader to the right captured spans/traces.
expected_spans:
  - match_field: <name | model | trace_id | metadata.<key>>  # what the matcher keys on
    match_pattern: <string>          # exact string or glob (* / ?), e.g. "checkout_summary"
    kind: <span | trace>             # whether the match identifies a span or a whole trace
    source: <observed | inferred>    # v9 — observed: read from real telemetry (verified); inferred
                                     # (default when absent): guessed from static source. A verified
                                     # OBSERVED entry supersedes any INFERRED guess for the same site.
    confidence: <high | medium | low>  # REQUIRED for inferred entries; optional/moot for observed
                                     # (a verified span name has no uncertainty)

# CODE-TRACKED FACTS (schema 0.15.0). These describe the call site's SOURCE, not its traffic, so the
# platform keeps them in sync as the code changes and re-imports them on every push. Both are OPTIONAL.
#
# `output_schema` distinguishes ABSENT from EMPTY, because it has TWO writers: this bundle and the
# platform's own agentic synthesis. Omitting the key means "this shard does not carry the fact" and the
# platform keeps whatever it captured — NOT "this call site declares no structured output". To assert
# the code declares none, emit `output_schema: null`, which clears the stored capture.
#
# `tools` does NOT: the bundle is its only writer, so omitting it means "no tools" and clears the
# stored list. There is no third state to express.

# The structured output the call site's code declares, verbatim as a JSON Schema. Read by the
# platform's Malformed Output classifier, which validates each span's output against it. A
# declaration here WINS over the platform's own capture — the repo is the source of truth, so editing
# this file is how a user corrects a stale one.
output_schema: <JSON Schema object | null>

# The tools the call site declares to the model (schema 0.15.0). `name` is the only required field:
# a tool we can name but not fully specify is still worth declaring, and dropping it would silently
# hide exactly the tools we understand least.
tools:
  - name: <string>
    description: <string | null>
    input_schema: <JSON Schema object | null>   # the tool's argument contract
    source: <string | null>                     # file:line of the declaration

# From captured traces (populated platform-side; optional)
source_spans:
  - trace_id: <hex>
    span_id: <hex>
    parent_span_id: <hex | null>
    service_name: <string | null>
    timestamp: <iso8601 | null>

observed:
  first_seen: <iso8601 | null>
  last_seen: <iso8601 | null>
  error_rate: <float | null>
  refusal_rate: <float | null>
  p50_latency_ms: <int | null>
  p95_latency_ms: <int | null>
  p50_tokens_in: <int | null>
  p95_tokens_in: <int | null>
  p95_tokens_out: <int | null>
  cost_estimate_usd: <float | null>
  redaction_state: <none | partial | redacted | unknown>

discovered_at: <iso8601>
```

`invocation` (schema 0.9.0) records *how* the model is reached, so indirect calls
stay visible: `sdk` (in-process provider/framework SDK — the default and the only
value pre-0.9.0), `cli_agent` (the repo shells out to an agent/LLM CLI such as
`claude`, `opencode`, `aider`, `ollama`), `http` (raw HTTP to a model endpoint or
gateway, no SDK), `sandbox_agent` (an agent/LLM run inside a sandbox runner such as
e2b/modal/daytona/docker). Absent is treated as `sdk`. Indirect sites usually have
`system_prompt: null` (the prompt lives in the external tool) and no enforced output
schema, which shifts their failure surface.

## `.tessary/pipeline/chains.yaml`

```yaml
chains:
  - id: <string>                     # chain::<short_snake_case_label>
    name: <string>
    call_site_ids: [<string>, ...]
    detection_method: <trace_confirmed | state_mediated | sequential_composition | ensemble>
    confidence: <high | medium | low>
    rationale: <string>
    ensemble_span_ids: [<hex>, ...]  # optional; only when detection_method == ensemble
```

## `.tessary/pipeline/failure_modes/<call_site_id>.yaml`

One shard per call site, holding only that site's `scope: single_call` failures.

```yaml
failure_modes:
  - id: <string>                     # <call_site_id>::<name>
    scope: single_call
    call_site_id: <string>
    chain_id: null
    name: <string>
    description: <string>
    severity: <low | medium | high>
    layer: <A | B | C>
    pack_ids: [<string>, ...]        # legacy tag set; new failure modes leave it empty
    compliance_tags: [<string>, ...]
    taxonomy_node_id: <string>
    grader_id: <string | null>       # <failure_mode_id>::grader; null when deferred
    grader_deferred: <bool>          # true = recorded but no grader authored yet
```

`grader_deferred`: only `severity: high` failures get graders in the first authoring sweep
(`grader_deferred: false`, `grader_id` set). Medium/low failures are recorded with
`grader_deferred: true` and `grader_id: null` until a later authoring pass — the observer's, or a
human's — clears them.

## `.tessary/pipeline/failure_modes/_chains.yaml`

All chain failures live in one shard (small set, cross-chain visibility helps
during review):

```yaml
failure_modes:
  - id: <string>                     # <chain_id>::<name>
    scope: chain
    call_site_id: null
    chain_id: <string>
    name: <string>
    description: <string>
    severity: <low | medium | high>
    layer: null                      # chain failures are not in the A/B/C layering
    pack_ids: [<string>, ...]
    compliance_tags: [<string>, ...]
    taxonomy_node_id: <string>
    grader_id: <string | null>       # null when deferred
    grader_deferred: <bool>          # same semantics as single_call
```

## `.tessary/pipeline/quality_dimensions/<call_site_id>.yaml`

One shard per **judgment** call site. Quality dimensions are the continuous "how good is the
output" axes scored 1–5 — distinct from failure modes, which are binary "what went wrong" checks.
Each becomes a `kind: score` grader (see below). Mechanical sites (`embedding`, strict-schema
`extract`, pure `guardrail`/`moderation`) have no shard.

```yaml
quality_dimensions:
  - id: <string>                     # <call_site_id>::<dim_name>
    call_site_id: <string>
    scope: single_call
    name: <string>                   # snake_case axis name
    description: <string>            # what this axis measures
    why_it_matters: <string>         # why a sustained dip hurts the product
    rubric_levels:                   # anchored 1–5; each a concrete, observable description
      "5": <string>
      "4": <string>
      "3": <string>
      "2": <string>
      "1": <string>
    grader_id: <string>              # <id>::grader — the kind: score grader
```

Quality dimensions are never deferred: every judgment call site must carry at least one, and each
is graded in the first authoring sweep. The bundle validator enforces both (a judgment-shape call
site with zero quality dimensions is an error, in full and `--partial` mode alike).

## `.tessary/pipeline/taxonomy.yaml`

```yaml
taxonomy:
  - id: <string>                     # tax::<slug> or tax::<parent>::<sub>
    name: <string>
    description: <string>
    parent_id: <string | null>
    example_call_site_ids: [<string>, ...]
    example_chain_ids: [<string>, ...]
```

## `.tessary/pipeline/capabilities.yaml`

The product-level inventory of what its agents can reach — tools, skills, MCP servers, subagents —
read out of the CODE (schema 0.15.0). OPTIONAL; a bundle without this shard declares no inventory.

This exists because everything the platform knows about an agent's capabilities today is inferred
from traffic: its behaviour-drift detector learns a project's normal action skeleton from that
project's own traces, so "a new capability appeared" and "an established step quietly disappeared"
are statistical inferences over what the agent happened to do. Against a declared inventory they
become a **diff** — a capability in the code but never in traces is dead, one in traces but not in
the manifest is genuinely unexpected — and today those two are indistinguishable.

```yaml
capabilities:
  - name: <string>                 # the identifier the code declares
    kind: <tool | skill | mcp_server | subagent>   # default `tool` when absent
    description: <string | null>
    source: <string | null>        # file:line of the declaration
    call_site_ids: [<string>, ...] # call sites that can reach it; empty/absent = product-wide
```

At most ONE capabilities shard per bundle — two means one of them is silently losing, so the
platform's importer rejects the bundle rather than pick. Enforced platform-side (its parser sees
the whole tree, including a `.yml` spelling).

### Worked example — the parity anchor

The two blocks below are the **canonical fixture** for the code-tracked facts. The platform's
`CodeFactContractParityTest` parses these `parity-anchor` blocks out of its vendored copy of this
document and asserts every key binds to a field its importer actually consumes — the bundle parser
drops unknown YAML keys by design, so a misspelled key is otherwise silent from both sides. The
markers delimit the exact text; a change to either block must land with the platform re-syncing its
vendored copy in the same pair of PRs.

<!-- parity-anchor: call_site begin -->
```yaml
id: support.answer
provider: anthropic
invocation: sdk
shape: rag_answer
output_schema:
  type: object
  properties:
    answer: {type: string}
  required: [answer]
tools:
  - name: search_docs
    description: full-text search over the handbook
    input_schema:
      type: object
      properties:
        query: {type: string}
    source: src/tools/search.py:41
  - name: escalate
```
<!-- parity-anchor: call_site end -->

<!-- parity-anchor: capabilities begin -->
```yaml
capabilities:
  - name: search_docs
    kind: tool
    description: full-text search
    source: src/tools/search.py:41
    call_site_ids: [support.answer]
  - name: refund-policy
    kind: skill
```
<!-- parity-anchor: capabilities end -->

## `.tessary/graders/<call_site>/<failure>.yaml`

One file per grader. Required keys (see `contract/grader.schema.json` for the
full schema):

```yaml
id: <string>                         # canonical, with `::`
scope: <single_call | chain | trace>
failure_mode_id: <string>
call_site_id: <string | null>        # required when scope == single_call or trace
chain_id: <string | null>            # required when scope == chain
name: <string>

kind: <llm_judge | deterministic | execution | agentic>
_body_source: <platform | platform-materialized | human>   # present on kind=llm_judge / score only.
                                     # platform (v8) — body DEFERRED, judge_prompt/rubric empty; the
                                     #   platform authors it from real traces after import.
                                     # platform-materialized (v9) — the platform synced the generated
                                     #   body back into this repo; judge_prompt(/rubric) is PRESENT and
                                     #   frozen (_meta.locked_fields), with _meta.materialized_at/body_digest.
                                     # human (v9) — a human edited a materialized body; PRESENT and the
                                     #   new authoritative body that syncs back upstream.
                                     # Omit for deterministic/execution/agentic.
applies_when: <string | null>       # always LLM-evaluated (inline for judge/score; a
                                     # separate LLM gate for deterministic). No applies_when_check (v6).

# kind == llm_judge:
# judge_prompt / rubric are NOT authored in-repo for a fresh deferral (v8) — they carry
# `_body_source: platform` and the platform authors the judge body after import. (Pre-v8 files may
# still carry an inline judge_prompt+rubric with no _body_source; that shape still validates.)
# v9: once the platform materializes the body back into the repo, the file carries a PRESENT
# judge_prompt+rubric with `_body_source: platform-materialized` (or `human` after a human edit) —
# frozen, never re-authored.

# kind == deterministic:
deterministic_check: <string>        # prose spec of the check; the platform generates + runs the code

# kind == execution:
execution_spec: <string>

# kind == agentic (verdict produced by an agent in a sandbox; declared here, run by the platform):
agent_spec:
  harness: opencode
  sandbox: {image: <string>, network: <none | egress | full>}
  allowed_tools: [<string>, ...]
  task_prompt: <string>              # grading task; ends in one binary decision
  verdict_contract: <string>         # how the agent emits PASS/FAIL
  budgets: {max_turns: <int>, max_cost_usd: <float>, timeout_s: <int>}   # optional

# v7: graders carry NO self_tests. Behavior is calibrated platform-side against golden
# datasets (real labeled spans associated with the grader in evals-platform), not via
# per-grader self-test cases.

confidence: <high | medium | low>
rationale: <string>
taxonomy_node_id: <string>

block_on_fail: <bool | null>
pack_ids: [<string>, ...]            # legacy tag set; new graders leave it empty
dataset_refs:
  - trace_id: <hex>
    span_id: <hex>
    label: <string | null>
  - file: <"path:line">

_meta:                              # optional block; when present, author /
                                     # synthesized_at / synth_inputs_digest are required
  author: <string>
  author_contract_version: 8         # optional even when _meta is present; stamped by the author
  synthesized_at: <iso8601>
  synth_inputs_digest: <hex>
  locked_fields: [<field>, ...]
  human_edited: <bool>
  materialized_at: <iso8601>         # v9 — set by the platform sync-back when the body was
                                     # materialized into this repo (present on materialized/human bodies)
  body_digest: <hex>                 # v9 — canonical SHA-256 of the materialized body; a mismatch on
                                     # _body_source: platform-materialized signals a human edit (→ promote to human)

# Present only when validation was unable to produce a clean grader after retries.
_validation_error: <string | null>
```

### Validator invariants

The full rule list lives in `contract/AUTHORING_CONTRACT.md` (canonical for humans) and
`contract/grader.schema.json` (canonical for machines). The **bundle validator is platform-owned**
(it rides in the platform's observer sandbox as `tessary-evals-validate`, and runs in the
platform's CI); this repo carries the contract documents, not the enforcer.

Bundle-level invariants (FM↔grader bijection, chain DAG acyclicity, duplicate IDs, taxonomy
reachability, coverage gates) are enforced by the bundle validator over the assembled shards.

## `.tessary/sops/<call_site_id>.yaml`

One **v3 SOP-conformance file per call site** — written by `/evals:derive-sop`, kept true by
`/evals:reconcile-sop`, validated by the plugin's bundled `sop_lint.py`. Seven concepts, pure
meaning, zero mechanism:

```yaml
agent: <call_site_id>                  # verbatim; same value as the span tag

intents:
  <intent-id>: {means: "<one sentence of demand-side meaning>"}

observations:
  <obs-id>: "<one atomic sentence>"    # or the frame-exemplar mapping form:
  <obs-id>:
    is: "<one atomic sentence>"
    counts: ["<mini-scenario>", ...]       # 2–3 adjudicated exemplars total
    counts_not: ["<mini-scenario>", ...]

rules:
  - in: <intent-id | [ids] | any>
    when: <obs-id>                     # optional
    unless: <obs-id>                   # optional
    at: <session-start | session-end>  # optional
    expect: <obs-id | {tool: t, before: it|t2, paired_by: k, within: conversation|turn}>
    # — or —  never: <obs-id | {tool: t}>
    # optional: either: [<obs-id>, ...] · of: <delegate-role>
```

The authoritative schema design note is `classifiers/experiments/SCHEMA-V3.md` in the
evals-platform repo; the vendored linter (`sop_lint.py`, Python 3 + PyYAML only) is the local
enforcer of its style laws (atomicity, specificity/hedge words, exemplar shape, `never:`
polarity). Nothing mechanical — detectors, thresholds, patterns, label files, provenance
anchors — is ever written into this file; the platform compiles all of that server-side.

**The bundle importer does not read `sops/` today.** Getting the SOP into the platform's
conformance engine is an ops-side seam (no tenant-facing endpoint yet), so the on-disk schema
version is unchanged — the file rides in the repo, already authored and linted, until that seam
opens.

## Retired artifacts (pre-0.16.0)

Local synthesis used to write more than the shards above. These are **no longer produced**; a
bundle still carrying them parses fine — the importer ignores them:

| Artifact | What it was |
| --- | --- |
| `pipeline/packs.yaml`, `pipeline/priorities.yaml` | Synthesis-run inputs (pack engagement, site ordering) |
| `datasets/<call_site_id>.jsonl` | Replayable rows captured from fetched traces — superseded by platform-side golden datasets keyed to real spans |
| `report.md`, `index.html` | The local visual report — superseded by the platform UI |
| `.synth-lock.yaml` | Re-run safety hashes for the local synthesizer |
| `.cache/` | Local trace-fetch cache (was always gitignored) |
