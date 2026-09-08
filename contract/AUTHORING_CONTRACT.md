# Grader-author contract (v9)

This document defines the interface between a **bundle-authoring orchestrator** and any **grader author** it invokes. Today the orchestrator is the **evals-platform observer** (its sandboxed agent authors grader definitions in-repo and proposes them as draft PRs); historically it was this plugin's `synthesize-graders` skill, and the contract deliberately survives that move unchanged — an author is swappable, and closed-source or third-party authors (e.g. `evals-prompt`) declare conformance and become drop-in replacements. A human editing grader YAML by hand in `.tessary/` is bound by the same rules.

- **Authoritative schema**: [`grader.schema.json`](./grader.schema.json) — the on-disk grader YAML.
- **Authoritative enforcer**: the bundle validator, which is **platform-owned** (`tessary-evals-validate` in the platform's authoring sandbox, and the platform's CI) — it runs the schema rules plus cross-field invariants, per-file and `--bundle` cross-references. References to `validate.py` below mean this validator.
- **Contract version**: 9. **v9 is author-transparent** — it does not change what a grader author emits for a fresh synthesis (a deferred body is still `_body_source: platform` + empty body; the v9-removed fields were orchestrator-owned or never author-emitted; the materialized/human body states are produced by the platform's sync-back, never by an author). So an author that already conforms to v8 conforms to v9 unchanged, and `_meta.author_contract_version` stays `8` for those authors (see § "Declaring conformance"). v8 **defers judge-prompt authoring to the platform** for `kind=llm_judge`/`score`: the author emits the grader *definition* (kind, `applies_when`, `rubric_levels`+`score_scale` for score, `confidence`, `rationale`) plus a top-level `_body_source: platform` marker, and does **not** author `judge_prompt`/`rubric`. The platform expands the verdict body on import. `kind=deterministic`/`execution`/`agentic` bodies are unchanged — still plugin-authored inline.

## What changed in v9

- **Three redundant grader fields removed.** `owner`, `cost_budget_tokens`, and `latency_budget_ms_p95` are gone from the grader schema (they were write-only / orchestrator-default and unread by the platform importer), along with the grader-level `compliance_tags` (the **failure-mode-level** `compliance_tags` is unchanged and still consumed). The long-dead `applies_when_check` (feature-removed in v6) is fully dropped — `validate.py` no longer special-cases it; a stray key on a legacy file is simply ignored. None were author-emitted, so authors are unaffected.
- **Grader body LIFECYCLE — `_body_source` is now a three-state enum.** `platform` (DEFERRED, body empty, as v8) is joined by `platform-materialized` (the platform generated the body and synced it back into the repo via its GitHub integration — the inline `judge_prompt`/`rubric` is now PRESENT and frozen) and `human` (a human edited a materialized body in-repo — also PRESENT, and the new authoritative body that syncs back upstream). The materialized/human states are produced by the platform sync-back and by human curation, **never by an author**: a fresh author still emits only `platform` + empty body. `_meta` gains optional `materialized_at` + `body_digest` (a canonical body hash; a mismatch on a `platform-materialized` grader signals a human edit and must promote it to `human` + `human_edited: true`). The freeze: a `platform-materialized`/`human` body must never be re-authored or overwritten by synthesis — it is carried forward verbatim, locked via `_meta.locked_fields: [judge_prompt(, rubric)]`.
- **`expected_spans` gains `source: observed | inferred`** (call-site shard; default `inferred`). An `observed` entry was read from real OTel/trace telemetry and is a verified fact, so the platform trusts it unconditionally and `confidence` is moot/optional for it; `inferred` is the v8 best-effort guess where `confidence` still ranks. Orchestrator-owned; authors do not write it.

## What changed in v8

- **Judge-prompt authoring moves to the platform for `llm_judge` / `score`.** For these two kinds the author now emits only the *definition* — `kind`, `applies_when` (llm_judge only), `rubric_levels`+`score_scale` (score only), `confidence`, `rationale` — plus a top-level marker **`_body_source: platform`**, and does **NOT** emit `judge_prompt` or `rubric`. On import the platform synthesizes the runtime verdict body (the judge system prompt, the rubric prose / scoring instruction) from the definition. This keeps the judge-prompt craft (injection hardening, output-JSON contract, scoring discipline) in one place, owned by the platform, rather than re-authored per grader by every plugin author.
- **`_body_source: platform` is the deferral marker.** Its only legal value is `platform`. It is valid **only** on `kind=llm_judge` / `kind=score`. When present, the grader **must not** carry a non-empty `judge_prompt`/`rubric` (`validate.py` flags an author that set the marker but forgot to actually drop the body). When **absent**, the v7 rule still holds — `kind=llm_judge` requires inline `judge_prompt`+`rubric`, `kind=score` requires inline `judge_prompt` — so deterministic/execution/agentic graders and any legacy inline-body llm_judge/score files keep validating unchanged.
- **`kind=deterministic`/`execution`/`agentic` are untouched.** Their bodies (`deterministic_check`, `execution_spec`, `agent_spec`) remain fully plugin-authored. `_body_source` does not apply to them.
- **New orchestrator-owned call-site field `expected_spans`.** The discovery step that locates each call site in code (writing `surrounding_code`/`file_hint`/`line_hint`) now also extracts the telemetry nomenclature the instrumentation emits — OTel `start_span("…")`, Langfuse `name=` / `@observe(name=)`, the enclosing function name (the SDK default span name), etc. — and records it as a best-effort `expected_spans` list on the call-site shard so the platform can bind a grader to the right captured spans/traces. This is an **orchestrator** field; authors do not write it. See `output_format.md` § call_site and `prompts/per_site_kit.md` § 1.

## What changed in v7

- **`self_tests` are gone.** Authors no longer hand-write sample outputs + expected verdicts. Behavioral calibration moves to **golden datasets**: a curator associates a golden dataset (real captured spans) with a grader in evals-platform and labels each item with the expected verdict/level per `(grader, dataset_item)`; the platform runner then scores the grader against those real spans. This removes the duplicated, buried, hard-to-grow per-grader test suite.
- **`self_test_pass_rate` / `self_test_variance` are removed.** There is no step-6 self-test calibration. Calibration signal now comes from golden-dataset runs on the platform.
- **The `applies_when ↔ not_applicable` self-test invariant is removed.** Out-of-scope items are surfaced as `not_applicable` by the platform runner at golden-run time; the author no longer asserts a `not_applicable` self-test. `applies_when` itself is unchanged (still author-owned, still always LLM-evaluated).
- Everything else the author owns (the per-`kind` verdict body, `applies_when`, `confidence`, `rationale`) is unchanged from v6.

## What changed in v6

- **`applies_when` is always evaluated by an LLM at runtime.** For `kind=llm_judge`/`score` it rides inline in the judge prompt, as before. For `kind=deterministic` the platform now runs a **separate LLM applicability gate** before the body and skips out-of-scope outputs — so the `deterministic_check` is **gate-free**: it must implement only the failure check and assume the input is in scope (never decide applicability itself).
- **`applies_when_check` is removed.** It was the code-evaluable mirror compiled into a deterministic body; the gate is never compiled now, so do not emit it. (v9: the field is fully dropped from the contract — `validate.py` no longer flags it; a stray key on a legacy file is ignored.)
- **Trace deterministic checks receive structured input.** A `scope: trace` deterministic check runs against `input.messages` (turns of `{ role, content, tool_uses: [{ name, input }] }`) and `input.tool_uses` (every tool call across turns, flattened) — not just a flattened transcript string. The platform supplies this structured shape from the golden dataset's real trace spans.

## What changed in v5

- Call sites carry a new orchestrator-owned field **`default_grade_mode: per_turn | per_conversation`** (default `per_turn`). The orchestrator sets `per_conversation` when it detects a multi-turn site (turns sharing a trace at a `conversational_turn` / `agent_step` site) and then authors that site's failure-mode graders as `scope: trace`. Authors do not set this field.
- **Trace-grader history sourcing is pinned to the final turn's self-contained input** (the whole transcript lives in the last turn's `input`; the runner grades the latest turn per trace). This is a runner/contract clarification. See § "Trace graders".

## What changed in v4

- A new grader **`scope: trace`** grades the **final turn** of a multi-turn session at one call site, given the prior n-1 turns as context. It anchors to a `call_site_id` exactly like `single_call`. Used for cross-turn coherence failures.
- A new grader **`kind: agentic`** produces a **binary verdict** by running an agent in a sandbox (via opencode) — e.g. running `git diff` / tests / file exploration — instead of a single judge call. The author emits an **`agent_spec`**; the runner (evals-platform) executes it. The plugin never runs an agentic grader. Use it when the verdict requires inspecting the result the agent produced, not just the output text.
- Both are additive: a v3 author remains conformant for everything else; the orchestrator only needs a v4 author when it requests a trace or agentic grader.

## What changed in v3

- A new grader **`kind: score`** scores a **quality dimension** on an anchored 1–5 rubric, instead of returning a binary verdict. These are the subjective grey-area quality evals — tracked as a pass-rate/level trend over time, never a pass/fail gate.
- The orchestrator passes a `quality_dimension` block (instead of `failure_mode`) when it wants a score grader. The author returns `kind: score` with `rubric_levels` and `score_scale`.
- An author that cannot produce `kind: score` should still accept the `quality_dimension` input but may decline; the orchestrator then falls back to the bundled author for that grader.

## What changed in v2

- `applies_when_check` was a new author-owned field: a code-evaluable mirror of `applies_when` required when `kind=deterministic` AND `applies_when` is non-empty. (`applies_when_check` was feature-removed in v6 and dropped from the contract entirely in v9; the per-grader self-test fields were removed in v7.)
- The orchestrator writes a `_meta` provenance block onto every grader file and respects `_meta.locked_fields` on re-run. Authors should not touch `_meta`.
- Per-grader operational fields (`block_on_fail`, `dataset_refs`) are optional and orchestrator-owned. (v9 removed the formerly-listed `owner`, `cost_budget_tokens`, and `latency_budget_ms_p95`.)

## Roles

| Field on the on-disk grader | Who fills it in |
| --- | --- |
| `id` | orchestrator (derived from `failure_mode_id` / `chain_id`) |
| `scope` | orchestrator |
| `failure_mode_id` | orchestrator |
| `call_site_id` / `chain_id` | orchestrator |
| `name` | orchestrator |
| `taxonomy_node_id` | orchestrator (Phase D taxonomy clustering — patched onto graders/entries after single_call grader synthesis; empty until then) |
| `block_on_fail`, `dataset_refs` | orchestrator (sourced from call-site observed stats / curator input) |
| `_meta` | orchestrator (incl. v9 `materialized_at` / `body_digest`, set by the platform sync-back) |
| `expected_spans` | orchestrator (discovery step — telemetry nomenclature read from the call site's code; written on the call-site shard, consumed by the platform; v9 `source: observed` set when grounded in real telemetry) |
| `output_schema`, `tools` (call-site shard) | orchestrator/platform (schema 0.15.0 — code-tracked facts read from the call site's source; never author-emitted) |
| `capabilities` (`pipeline/capabilities.yaml`) | orchestrator/platform (schema 0.15.0 — the product's tool/skill/MCP/subagent inventory read from the code) |
| `kind` | **author** |
| `_body_source` | **author** sets `platform` to defer (v8); the platform sync-back sets `platform-materialized`, and a human edit promotes to `human` (v9) |
| `applies_when` | **author** (always LLM-evaluated at runtime) |
| `judge_prompt` | platform (expanded after import — v8; **materialized back into the repo, then human when edited** — v9; was author ≤ v7) |
| `rubric` | platform (expanded after import — v8; **materialized back into the repo, then human when edited** — v9; was author ≤ v7) |
| `deterministic_check` | **author** (when `kind=deterministic`) |
| `execution_spec` | **author** (when `kind=execution`) |
| `agent_spec` | **author** (when `kind=agentic`) |
| `confidence` | **author** |
| `rationale` | **author** |

The orchestrator does **not** edit author-owned fields after they're written. If a returned grader looks wrong on inspection, the orchestrator re-invokes the author with refined context — it does not patch the YAML. Behavioral calibration happens later and separately, platform-side, by running the grader against its associated golden datasets (real labeled spans) in evals-platform.

## Input the orchestrator passes to the author

```yaml
failure_mode:
  id: <string>            # e.g. checkout::extraneous_action_items
  name: <string>          # short human-readable
  description: <string>   # 1-2 sentences — what failure looks like in production
  severity: low | medium | high
  layer: A | B | C | null # A = mechanical, B = judgmental, C = adversarial/operational
  scope: single_call | chain | trace

scope: single_call | chain | trace
# scope=trace is anchored to one call site (like single_call) but grades the final turn
# given prior turns; the call_site block below is supplied just as for single_call.

# When scope = single_call or scope = trace:
call_site:
  id: <string>
  intent: <string>
  constraints: [{kind, description, enforcement}]
  prompt_text: <string>
  shape: summarize | extract | rag_answer | classify | draft | route | tool_call
       | agent_step | conversational_turn | embedding | rerank | guardrail
       | moderation | ensemble_vote | other
  sample_count: <int | null>
  observed: <observed stats block from pipeline.yaml, optional>
  sample_outputs: [<string>, ...]  # optional; up to ~5 representative captured outputs

# When scope = chain:
chain:
  id: <string>
  call_site_ids: [<string>]
  detection_method: trace_confirmed | state_mediated | sequential_composition | ensemble
  rationale: <string>
call_sites: [<call_site>...]   # one per id, in chain order

product_context:           # optional but recommended
  domain: <string | null>
  brand_voice_signals: [{label, evidence}]
  regulatory_context: [{label, evidence}]

existing_grader:           # optional — present only when regenerating
  judge_prompt: <string>
  rubric: <string>
  applies_when: <string | null>
  locked_fields: [<field>, ...]    # author MUST preserve these verbatim

validator_feedback:        # optional — present on retry after a validate.py failure
  attempt: <int>           # 1-based; only set on retries (so attempt >= 2)
  errors: [<string>]       # stderr lines from validate.py
```

## Output the author returns

Raw YAML, ready for the orchestrator to splice into the on-disk grader file. **No prose around it.** Only the author-owned fields:

```yaml
kind: llm_judge | deterministic | execution | agentic
_body_source: platform           # v8 — REQUIRED for kind=llm_judge (and kind=score). Marks the
                                 # verdict body as platform-deferred; the platform expands
                                 # judge_prompt/rubric on import. Do NOT also emit judge_prompt/rubric.
                                 # Omit entirely for deterministic/execution/agentic.
applies_when: |                  # OMIT entirely when always applicable. Always LLM-evaluated:
                                 # inline for llm_judge/score, a separate LLM gate for deterministic.
  natural-language predicate deciding whether the check is in scope
# judge_prompt / rubric are NO LONGER author-emitted (v8): for kind=llm_judge the author sets
# `_body_source: platform` above and the platform authors the judge body on import.
deterministic_check: |           # required for kind=deterministic. GATE-FREE: implement only the
                                 # failure check; assume the input is in scope (the applies_when LLM
                                 # gate filters out-of-scope outputs first). For scope=trace, reason
                                 # over input.messages / input.tool_uses, not a flattened string.
  precise rule a code function can implement
execution_spec: |                # required for kind=execution
  how to run the output and what counts as pass/fail
agent_spec:                      # required for kind=agentic (see § "Agentic graders")
  harness: opencode
  sandbox: { image: <string>, network: none | egress | full }
  allowed_tools: [bash, read, git]
  task_prompt: <string>          # the grading task; ends in one binary decision
  verdict_contract: <string>     # how the agent emits PASS/FAIL the runner can parse
  budgets: { max_turns: <int>, max_cost_usd: <float>, timeout_s: <int> }   # optional
confidence: high | medium | low
rationale: <string>              # one sentence — user impact, not mechanics
```

> **No `self_tests` (v7).** Authors no longer emit per-grader self-test cases. A grader's behavior is calibrated platform-side against **golden datasets** — real captured spans associated with the grader and labeled per `(grader, dataset_item)` in evals-platform — not against hand-authored samples in the grader file.

### Invariants the author must satisfy

1. **Gate-free deterministic body** (v6): `applies_when` is always evaluated by an LLM — never compiled. Do **not** emit `applies_when_check`, and do **not** make `deterministic_check` decide applicability (it must assume the input is in scope; the LLM gate filters out-of-scope outputs first). `validate.py` flags a stray `applies_when_check`.
2. **`kind` body match (v8)**: `kind=llm_judge` emits `_body_source: platform` and **no** `judge_prompt`/`rubric` (the platform expands the body on import); `kind=score` emits `_body_source: platform` and **no** `judge_prompt` (it still emits `rubric_levels`+`score_scale`, which define the score axis, not the judge prose); `kind=deterministic` requires `deterministic_check`; `kind=execution` requires `execution_spec`; `kind=agentic` requires `agent_spec` (and no `judge_prompt`/`rubric`). `_body_source` is valid **only** on `llm_judge`/`score`; setting it elsewhere is a validation error. (Pre-v8 files with an inline `judge_prompt`+`rubric` and no `_body_source` still validate — the requirement only relaxes *when* the marker is present.)
3. **Locked fields**: if `existing_grader.locked_fields` is present on the input, the returned YAML must reproduce the listed fields **verbatim** (the orchestrator double-checks and rejects mutated locked fields).

Everything else (id shape, taxonomy node, cross-reference against `pipeline.yaml`, `_meta` provenance) is the orchestrator's responsibility.

## Trace graders (v4)

When the orchestrator passes `scope: trace`, the failure can only be judged with the conversation
history (a cross-turn coherence failure on a `conversational_turn` / `agent_step` site). The author
produces the **same author-owned fields as a single_call grader** (any failure-catching `kind`); the
difference is purely in how the platform feeds the grader at runtime (the prior n-1 turns as context
plus the final turn it judges), not in anything the author writes.

For a **trace `llm_judge`** grader, the body is platform-deferred (v8): emit `_body_source: platform`
and capture the contextual framing in `applies_when` + the failure-mode definition the platform receives —
the platform authors a judge prompt that judges the final turn **in the context of** the prior conversation
(e.g. "Given the conversation so far, does the final assistant turn stay consistent with commitments and
constraints established earlier?"). That contextual framing is the whole point of the scope; the author no
longer hand-writes the judge prompt.

For a **trace `deterministic`** grader, the `deterministic_check` runs against the structured trace —
`input.messages` (each turn `{ role, content, tool_uses: [{ name, input }] }`) and `input.tool_uses`
(every tool call flattened) — so reason over those, not a flattened transcript string. Keep the body
gate-free (the `applies_when` LLM gate handles scope).

**History sourcing (v5).** In production the runner does not stitch per-turn rows: it groups a
multi-turn site's spans by trace, takes the **latest turn**, and judges its `input` (which
already carries the whole transcript) plus its final output. The author is unaffected. The call site
that gets these graders is the one the orchestrator marked `default_grade_mode: per_conversation`.

## Agentic graders (v4)

When a failure can only be judged by inspecting the **result the agent produced** — did the repo end
up correct, does the `git diff` implement the request, do the tests pass — a static judge reading text
can't decide. The author emits `kind: agentic` with an `agent_spec` describing an agent the runner
launches in a sandbox. **The plugin only emits the spec; evals-platform executes it.** Verdicts are
binary (pass/fail) in v4.

```yaml
kind: agentic
agent_spec:
  harness: opencode              # the only harness today
  sandbox:
    image: <string>              # e.g. the project's CI image, or opencode/base:latest
    network: none                # most restrictive the task allows: none | egress | full
  allowed_tools: [bash, read, git]
  task_prompt: |                 # self-contained; ends in one binary decision
    Check out the session's repo at the final turn. Run `git diff <base> <head>` and decide
    whether the change correctly implements the user's request from the conversation. ...
  verdict_contract: |            # how the agent signals its result so the runner can parse it
    Print exactly one line `VERDICT: PASS` or `VERDICT: FAIL` as the last line of output.
  budgets: { max_turns: 20, max_cost_usd: 0.50, timeout_s: 600 }
confidence: high | medium | low
rationale: <string>
```

### Agentic-grader invariants

1. **`agent_spec` required**: `harness` (`opencode`), `sandbox.image`, `task_prompt`, and
   `verdict_contract` are all non-empty; `allowed_tools` is a list when present.
2. **Binary only (v4)**: the agent produces a `pass` / `fail` verdict. No 1–5 scoring in v4.
3. **No judge body**: do not emit `judge_prompt` / `rubric` / `deterministic_check` /
   `execution_spec` — the `agent_spec.task_prompt` is the grading instruction.
4. **Least privilege**: pick the most restrictive `sandbox.network` the task allows and the minimal
   `allowed_tools`. A grader that doesn't need network must set `network: none`.

## Score graders (v3)

When the orchestrator wants a quality-dimension score grader, it passes a `quality_dimension` block in place of `failure_mode`:

```yaml
quality_dimension:
  id: <string>                 # e.g. persona_decision::basis_selection_relevance
  name: <string>               # snake_case axis name
  description: <string>        # what this axis measures
  why_it_matters: <string>     # why a sustained dip hurts the product
  rubric_levels:               # anchored 1–5, each a concrete observable description
    "5": <string>
    "4": <string>
    "3": <string>
    "2": <string>
    "1": <string>
  scope: single_call

scope: single_call
call_site: { ... }             # same call_site block as for failure modes
product_context: { ... }       # optional, same as above
existing_grader: { ... }       # optional, on regeneration
```

The author returns the score-grader author-owned fields (v8 — `judge_prompt` is platform-deferred):

```yaml
kind: score
_body_source: platform         # v8 — the platform authors the scoring judge prompt on import
                               # from the rubric_levels/score_scale below. Do NOT emit judge_prompt.
rubric_levels:                 # carry forward (or sharpen) the dimension's anchored levels
  "5": <string>
  "4": <string>
  "3": <string>
  "2": <string>
  "1": <string>
score_scale: { min: 1, max: 5 }
confidence: high | medium | low
rationale: <string>            # one sentence — why this axis matters for the product
```

### Score-grader invariants

1. **`expected_level` range** (golden labels): when a curator labels a golden item for a score grader, the expected level is an integer within `score_scale` `[min, max]`. The platform enforces this at label time, not the author.
2. **Rubric fidelity**: `rubric_levels` keys must be the stringified integers covering `[min, max]`; each anchor must be concrete and observable.

Score graders never use `applies_when` / `not_applicable` (they always apply when the call site fires), and `block_on_fail` is always `false` (orchestrator sets it) — a score grader is a trend, never a gate.

## Retry semantics

The orchestrator (or its step-6 subagents) runs `validate.py` on every emitted grader. On failure it re-invokes the author with the original input **plus** a `validator_feedback` block (see input schema above). Up to **3 retries** total. After the third failure, the orchestrator writes the last attempt to disk with a top-level `_validation_error: <message>` key and continues — the operator handles it during review.

When `_validation_error` is set on a grader file, `validate.py` returns exit 0 on subsequent runs (it short-circuits all other rules). This is intentional: the file is already flagged for the operator (in the draft PR carrying it, and in the platform UI after import); a later cross-check pass should not double-flag it.

Authors should treat `validator_feedback.errors` as the primary signal for what to change on retry; the orchestrator does not paraphrase or filter the validator output.

## Declaring conformance

An author conforms to this contract when:

1. It accepts the input schema above (extra fields ignored).
2. It returns YAML matching the author-owned fields above.
3. Its output passes `validate.py` once the orchestrator splices in the orchestrator-owned fields.
4. It declares the contract version it conforms to via `contract_version:` — in its SKILL.md frontmatter for skill-based authors, or in its top-level markdown for bundled-procedure authors. The **current contract version is 8**; an author fully caught up declares `8`. The version requirement is a **minimum, evaluated per feature**: the orchestrator requires *at least* `3` for `kind: score`, and *at least* `4` for `scope: trace` or `kind: agentic`. v6 is NOT transparent for deterministic graders with a gate: a `< 6` author still emits the removed `applies_when_check` and may bake applicability into `deterministic_check`, which now fails `validate.py` / produces a double-gate. So for `kind: deterministic` with an `applies_when`, the orchestrator requires `6`. **v7 is author-transparent in the safe direction**: it only *removes* the self-test fields, so a `< 7` author that still emits `self_tests` is no longer conformant in form, but the orchestrator simply drops any stray `self_tests` it returns (the platform ignores the key). **v8 is NOT transparent for `kind: llm_judge`/`score`**: a `< 8` author still hand-writes `judge_prompt`/`rubric` and does not emit `_body_source: platform`, so its output is the old inline-body shape (which still *validates* — the marker only relaxes requirements — but is not the platform-deferred shape v8 wants). So whenever the orchestrator wants a platform-deferred body for an `llm_judge`/`score` grader it requires `8`; deterministic/execution/agentic graders stay author-transparent at `< 8`. New authors must declare `8` and defer the body for `llm_judge`/`score`. **v9 is fully author-transparent**: it removes three orchestrator-owned/never-emitted fields, adds the platform-driven body-materialization states (produced by the platform sync-back, never by an author), and adds the orchestrator-owned `expected_spans.source` field — none of which changes what an author emits for a fresh synthesis. So the contract version is `9` but the **author** contract version a conformant author declares stays `8` (`_meta.author_contract_version: 8`); there is no `9` author obligation, and the orchestrator does not require `9` of any author. (Should a future change add an author obligation, the per-feature minimum convention above applies.)

The orchestrator discovers authors by **two distinct invocation models**:

- **Skill-based authors** (e.g. `evals-prompt`) — invoked through the Skill tool, where one is available. The platform's own authoring agents use this model.
- **Inline procedure authors** — a markdown procedure read and followed inline by the authoring agent (the historical `authors/default/AUTHOR.md` shipped with this plugin's synthesis skill; retired with it).

This distinction matters: an agent that tries to invoke an inline procedure via the Skill tool will fail because there is no skill of that name. An orchestrator's subagent prompt must branch on author type.
