---
name: evals-prompt
description: Synthesize one production-grade grader — judge_prompt + rubric (or score levels, deterministic check, agent spec) + applies_when — from a single failure mode or quality dimension and its call-site context. Implements the evals-synth grader-author contract (v8), so any orchestrator following that contract picks this up as a drop-in author. Also usable standalone when a human wants to author or refine a single grader. Use when the user says "generate a grader prompt", "write a judge for this failure mode", or when an orchestrator delegates grader synthesis.
contract_version: 8
---

# evals-prompt — author one grader, well

You produce exactly one grader's author-owned fields. Nothing else. The orchestrator handles IDs, taxonomy, calibration, operational fields, `_meta`, and writing the on-disk grader file. You handle the words.

## Contract conformance

This skill implements **contract v8** (author-transparent through v9, the current contract). v8 *defers `judge_prompt` + `rubric` for `kind=llm_judge`/`score` to the platform*: the author emits the definition (`kind`, `applies_when`, `rubric_levels`/`score_scale`, `confidence`, `rationale`) plus a `_body_source: platform` marker, and the platform authors the verdict text on import.

> **The platform half of that deferral no longer exists.** tessary's Track A (open-core epic 8) removed grading root and stem, including the judge-prompt craft this skill used to point at (`backend/evaluation/src/main/resources/prompt-craft/grader-judge/`) and the import-time body expansion that read it. A bundle whose graders carry `_body_source: platform` will import — the platform skips the grader shard — but nothing will author the body. **Author the full body inline** (the standalone path below) until the cross-repo coordination this leaves open is settled; the plugin still ships grader authoring against a platform that no longer runs graders. Every citation below that named a `prompt-craft/grader-judge/*` file is left as prose describing what the craft covers, not as a path to read.

(v7, prior: removed inline `self_tests`; calibration is platform-side golden datasets — no longer true either: golden datasets went with grading.)

**The schema is not restated in this skill — that is what made the old version drift.** The authoritative shapes live in the orchestrator that ships the contract, vendored in tessary at:

- `contract/AUTHORING_CONTRACT.md` — the input the orchestrator passes you, the author-owned output fields, the invariants `validate.py` enforces, and the retry loop.
- `contract/grader.schema.json` — the on-disk grader YAML the schema validator checks.

**Read those two files before authoring.** If anything in this skill ever disagrees with them, *they win* — this skill is craft guidance layered on top of the contract, not a second copy of it. When invoked by the orchestrator you receive the input inline; when those files aren't reachable (pure standalone use), the field reference below is enough to work from.

## Why a dedicated skill

Authoring a good grader is its own craft. The contract defines *what shape* to emit; it deliberately does not tell you how to write a judge prompt that calibrates or an `applies_when` that gates honestly. That craft is what this skill adds — and what the bundled OSS `authors/default/` author can't do as well. Keep this skill small and sharp; raise prompt quality here without touching the orchestrator.

## What you receive

`AUTHORING_CONTRACT.md` § "Input the orchestrator passes to the author" is the authoritative input shape — read it rather than relying on a copy here. In brief: either a `failure_mode` block (→ a failure-catching grader) or a `quality_dimension` block (→ a `kind: score` grader), each with a `scope`, the matching `call_site` / `chain` block(s), and optional `product_context`, `existing_grader` (regeneration — reproduce any `locked_fields` verbatim), and `validator_feedback` (retry — see § Retry).

Beyond the field list, the inputs that change your *craft*:

- **`call_site.sample_outputs` / `call_site.observed`** — real captured outputs and stats. Your best signal for a rubric that reads against production-shaped output; don't settle for guessing from `prompt_text` when real outputs are present.
- **`failure_mode.layer`** (mechanical / judgmental / adversarial-operational) — steers the `kind` choice in step 1.
- **`call_site.shape`** — what the call is for (summarize, rag_answer, tool_call, conversational_turn, …); focuses the rubric. The full enum lives in the contract.

## What you produce

Raw YAML of **author-owned fields only** — no prose around it, and nothing the orchestrator owns (`id`, `failure_mode_id`, `call_site_id`/`chain_id`, `taxonomy_node_id`, `_meta`, operational fields; it splices those in).

The authoritative output shape — every field, when each is required, and the validation invariants — is `AUTHORING_CONTRACT.md` § "Output the author returns" plus `contract/grader.schema.json`. Don't reproduce it from memory. What you own, by `kind`:

| `kind` | verdict-body fields you author |
| --- | --- |
| `llm_judge` | `judge_prompt`, `rubric` |
| `deterministic` | `deterministic_check` (gate-free; `applies_when`, when set, is a separate LLM gate) |
| `execution` | `execution_spec` |
| `agentic` | `agent_spec` |
| `score` | `judge_prompt`, `rubric_levels`, `score_scale` |

Plus, on every grader: `applies_when` (optional gate), `confidence`, and the user-facing `rationale`. The steps below are how to author each well; the per-kind prompt files carry worked examples.

## The authoring procedure — follow in order

### 1. Decide `kind`

- **`deterministic`** — a regex, JSON-schema check, parser, or simple predicate suffices. Layer A failures (format, structural invariants, refusal-condition breaches). Cheap, no drift. Emit `deterministic_check` and keep it **gate-free** — if you set `applies_when`, the platform runs it as a separate LLM gate before your check, so do NOT emit `applies_when_check` and do NOT make the check decide applicability. For `scope: trace`, the check reads structured `input.messages` / `input.tool_uses`. Skip the rest of the judge-prompt steps.
- **`llm_judge`** — the failure requires reading the output for meaning. Layer B failures (faithfulness, helpfulness, calibration, tone, edge-case judgment). Most graders we author. Continue.
- **`execution`** — the output is code or a tool call that can be executed/validated mechanically by a fixed runner. Emit `execution_spec`. Rare.
- **`agentic`** — the verdict requires an agent to *inspect the result the agent under test produced* (run `git diff`, run tests, explore files) — a static judge reading text can't decide. Emit `agent_spec`; see `prompts/agentic.md`. Binary verdict only.
- **`score`** — only when the request is a `quality_dimension`, not a `failure_mode`. A 1–5 quality trend, never a pass/fail gate. Anchored 1–5 levels, each level a concrete observable.

Don't force everything to `llm_judge`. If the rubric is mechanical, write a `deterministic_check`. If it needs to run the artifact, use `execution`/`agentic`.

### 2. Write `applies_when` BEFORE the rubric

The most-skipped step, and the one that produces the most meaningless verdicts when omitted. Ask:

> "Is this rubric meaningful for *every* output from this call site, or only when X is true?"

Anchor X to the failure mode's `description`. If the rubric truly applies to every output, **omit the field**.

When `applies_when` is set, the platform runs it as a separate LLM gate before the verdict body, and the body's prompt must evaluate scope first and return `applicable: false` out of scope (step 3). Anchor the gate to one observable condition, never to a judgement.

### 3. Author the verdict body

Branch on `scope` and `kind`:

- single_call / chain / trace `llm_judge` → author inline, following the six elements listed below
- `score` → author inline: anchored 1–5 levels plus the same six elements
- `agentic` → `prompts/agentic.md` (the one body this skill still ships craft for)
- `deterministic` / `execution` → no judge prompt; write the `deterministic_check` / `execution_spec` rule precisely. (Deterministic code is generated and validated platform-side against the grader's labeled golden items — not authored here.)

For LLM-judge bodies these cover: role statement, declared input slots, the `applies_when` gate (when set, the prompt MUST tell the model to evaluate scope first and return `applicable: false` out of scope), the stepwise procedure, the ambiguity policy (default ambiguous-but-in-scope → `pass`), and the verdict shape `{ applicable, passed, score, rationale }`.

The judge prompt is the production artifact. Treat it as code. The customer reads it during code review, and it must run on any judge runtime with no hidden dependencies — the prompt is the entire interface.

### 4. Author `rubric` (llm_judge) / `rubric_levels` (score)

3–7 bullets for `llm_judge`, each a single concrete criterion the customer can scan. The patterns worth reaching for: paired pass/fail, counted criteria, weighted scoring, explicit tie-breaking. For `score`, author anchored 1–5 levels on the same principles.

Anti-pattern: "consider whether it sounds polite." Pattern: "Pass if the output cites a specific source from the memory; fail if no source is cited."

### 5. Set `confidence`

- **`high`** — unambiguous criteria; the rubric criteria cleanly separate pass from fail.
- **`medium`** — judgment calls remain; near-boundary cases are genuinely close.
- **`low`** — authoring relied on guesswork (sparse context, no sample outputs). Customer should review.

Calibration happens later, platform-side, by running the grader over its associated golden dataset items; your value here is the initial signal.

### 6. Write the user-facing `rationale`

One sentence the customer reads in the curation report. Tie it to user impact, not mechanics.

✅ "RAG answers in your codebase frequently cite documents not in the retrieval set, which misleads the user about where information came from."
❌ "Checks citations."

## Retry — when you're re-invoked with `validator_feedback`

The orchestrator runs `validate.py` on every grader you emit. On failure it re-invokes you with the original input **plus** `validator_feedback: { attempt, errors }`. Treat `errors` (raw `validate.py` stderr) as the primary signal — fix exactly what it names, re-emit the full author-owned YAML. Up to 3 retries; after that the orchestrator writes your last attempt with `_validation_error` for the operator.

## Optimise for: legibility, calibratability, portability

- **Legibility** — the customer reads this on a Tuesday afternoon during code review, 15 seconds per grader. Write accordingly.
- **Calibratability** — write criteria sharp enough that a soft rubric would visibly mis-grade real production output. Calibration runs platform-side over the grader's golden dataset items.
- **Portability** — the grader runs on any judge runtime (Anthropic, OpenAI, Bedrock, Braintrust, Phoenix, our backend) with no hidden dependencies. The `judge_prompt` / `agent_spec` is the entire interface.

## When invoked directly by a human (no orchestrator)

The user describes a failure mode (or quality dimension) and provides at least a call-site reference. Ask for what's missing — the `description` is the minimum. Output the same YAML fragment; the user pastes it into their grader file manually. Point them at `contract/grader.schema.json` if they need the full on-disk shape including orchestrator-owned fields.
