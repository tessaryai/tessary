#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""
validate.py — authoritative validator for synthesize-graders output.

Two modes:

    Per-file:
        python3 validate.py .tessary/graders/<file>.yaml
        python3 validate.py .tessary/graders/<file>.yaml --pipeline .tessary/

    Bundle (v0.4 — recommended at step 7):
        python3 validate.py --bundle .tessary/

    Bundle + pack filter:
        python3 validate.py --bundle .tessary/ --pack security

In v0.4 the pipeline is sharded under .tessary/pipeline/*.yaml; the validator
assembles a v0.3-compatible logical mapping via pipeline_io.load_pipeline()
and runs every check unchanged against that mapping. Passing a directory to
--pipeline (per-file mode) also routes through the shard loader.

Per-file mode enforces the rules defined in `contract/AUTHORING_CONTRACT.md` and
`contract/grader.schema.json`. Bundle mode runs every per-file check across the
whole directory plus global checks: FM↔grader bijection, chain DAG acyclicity,
duplicate IDs, orphan / unreachable taxonomy nodes, layer-A/B/C coverage gates,
pack ID resolution, dedup uniqueness, and `_meta` provenance shape.

The --pack <id> filter narrows the bundle to failures / graders that carry the
named pack_id and prints a compliance-tag coverage matrix.

Optional held-out human-labelled calibration set:
    python3 validate.py --bundle .tessary/ --calibration-set human_labels.csv

The CSV has columns `grader_id, sample_output, verdict` (verdict ∈ pass|fail|not_applicable).
The validator reports per-grader agreement against the rubric (informational; does not gate exit code).

Exit codes:
    0  — valid, or accepted-broken (a top-level `_validation_error:` key
         short-circuits all rules on that file)
    1  — invalid (errors printed to stderr, one per line)
    2  — usage / I/O error
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import sys
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any, Final, Literal, Mapping, Sequence

try:
    import yaml
except ImportError:
    print("validate.py: requires PyYAML. Install: pip install pyyaml", file=sys.stderr)
    sys.exit(2)

# Local import — pipeline_io ships in the same plugin dir as this script.
sys.path.insert(0, str(Path(__file__).resolve().parent))
import pipeline_io  # noqa: E402


# ----------------------------------------------------------------------------
# Constants — frozen so they can't be mutated by callers importing this module.
# ----------------------------------------------------------------------------

VALID_KINDS: Final[frozenset[str]] = frozenset({"llm_judge", "deterministic", "execution", "score", "agentic"})
# Kinds whose verdict body (judge_prompt / rubric) may be deferred to the platform
# via the `_body_source` marker. Deterministic/execution/agentic bodies are always
# plugin-authored and never carry the marker.
BODY_DEFERRABLE_KINDS: Final[frozenset[str]] = frozenset({"llm_judge", "score"})
# v9 widens the enum to the three-state body lifecycle:
#   platform              — DEFERRED: body empty, the platform expands it on import (v8).
#   platform-materialized — MATERIALIZED: the platform synced a generated body back into
#                           the repo; the inline body MUST be PRESENT (and is frozen).
#   human                 — HUMAN-EDITED: a human edited a materialized body in-repo; the
#                           inline body MUST be PRESENT and propagates back upstream.
VALID_BODY_SOURCES: Final[frozenset[str]] = frozenset({"platform", "platform-materialized", "human"})
FAILURE_KINDS: Final[frozenset[str]] = frozenset({"llm_judge", "deterministic", "execution", "agentic"})
VALID_SCOPES: Final[frozenset[str]] = frozenset({"single_call", "chain", "trace"})
# scope=trace anchors to a single call site (like single_call).
CALL_SITE_SCOPES: Final[frozenset[str]] = frozenset({"single_call", "trace"})
VALID_VERDICTS: Final[frozenset[str]] = frozenset({"pass", "fail", "not_applicable"})
VALID_CONFIDENCE: Final[frozenset[str]] = frozenset({"high", "medium", "low"})
# `_meta.grounding` — `observed`: the call site's real traces were fetched and read before authoring.
# The A.0 gate makes grounding unconditional, so this is the only value a synthesis can stamp.
VALID_GROUNDING: Final[frozenset[str]] = frozenset({"observed"})
VALID_LAYERS: Final[frozenset[str]] = frozenset({"A", "B", "C"})

# Failure-catching graders require these; score graders use a different set (below).
# Note: `taxonomy_node_id` is NOT required here. The taxonomy is built at Phase D,
# after Phase C emits single_call graders, so it is empty mid-run (partial mode).
# Its presence is enforced only at the final non-partial bundle gate — see
# `_bundle_taxonomy_assigned`. (`taxonomy_node_id` ownership is unchanged: the
# orchestrator fills it during clustering; only *when* presence is enforced moves.)
REQUIRED_SCALAR_FIELDS: Final[tuple[str, ...]] = (
    "id", "scope", "failure_mode_id", "name", "kind",
    "confidence", "rationale",
)
REQUIRED_SCALAR_FIELDS_SCORE: Final[tuple[str, ...]] = (
    "id", "scope", "quality_dimension_id", "name", "kind",
    "confidence", "rationale",
)

# Step 4.6 coverage gates the bundle mode enforces deterministically.
MIN_LAYER_A_PER_SITE: Final[int] = 3
MIN_LAYER_B_PER_SITE: Final[int] = 5
MIN_LAYER_C_PER_SITE: Final[int] = 3  # exempt for embedding shapes (handled separately)
MIN_CHAIN_FAILURES: Final[int] = 3

Scope = Literal["single_call", "chain"]
Grader = Mapping[str, Any]
Pipeline = Mapping[str, Any]


# ----------------------------------------------------------------------------
# Small predicates — pure, single-purpose.
# ----------------------------------------------------------------------------

def _is_nonempty_str(value: Any) -> bool:
    return isinstance(value, str) and value.strip() != ""


def _is_platform_deferred(g: Grader) -> bool:
    """DEFERRED state (v8): a kind=llm_judge / score grader whose verdict body the
    platform expands on import. The author emits the definition + `_body_source:
    platform` and omits the judge_prompt / rubric. Matches ONLY the literal "platform"
    — the v9 materialized values are NOT deferred (they require a present body)."""
    return g.get("_body_source") == "platform"


# ----------------------------------------------------------------------------
# Per-rule checks. Each returns a list of error strings; callers concatenate.
# ----------------------------------------------------------------------------

def _check_required_fields(g: Grader) -> list[str]:
    return [
        f"missing or empty required field: {key}"
        for key in REQUIRED_SCALAR_FIELDS
        if not _is_nonempty_str(g.get(key))
    ]


def _check_enums(g: Grader) -> list[str]:
    errors: list[str] = []
    if g.get("scope") not in VALID_SCOPES:
        errors.append(f"scope must be one of {sorted(VALID_SCOPES)}; got {g.get('scope')!r}")
    if g.get("kind") not in VALID_KINDS:
        errors.append(f"kind must be one of {sorted(VALID_KINDS)}; got {g.get('kind')!r}")
    if g.get("confidence") not in VALID_CONFIDENCE:
        errors.append(f"confidence must be one of {sorted(VALID_CONFIDENCE)}; "
                      f"got {g.get('confidence')!r}")
    return errors


def _check_scope_routing(g: Grader, scope: str | None) -> list[str]:
    errors: list[str] = []
    if scope in CALL_SITE_SCOPES:
        if not _is_nonempty_str(g.get("call_site_id")):
            errors.append(f"scope={scope} requires non-empty call_site_id")
        if g.get("chain_id") not in (None, ""):
            errors.append(f"scope={scope} must not set chain_id")
    elif scope == "chain":
        if not _is_nonempty_str(g.get("chain_id")):
            errors.append("scope=chain requires non-empty chain_id")
        if g.get("call_site_id") not in (None, ""):
            errors.append("scope=chain must not set call_site_id (implied by chain_id)")
    return errors


def _check_body_source(g: Grader, kind: str | None) -> list[str]:
    """Validate the `_body_source` marker across the v9 three-state lifecycle. It must
    be a known value and may appear only on body-deferrable kinds (llm_judge / score).
    Then it gates the body presence by state:
      - platform (DEFERRED)              → body MUST be empty (the platform expands it
                                            on import; a non-empty body means the author
                                            did not actually defer it).
      - platform-materialized / human    → body MUST be PRESENT (the platform synced a
        (MATERIALIZED / HUMAN-EDITED)       generated body back, or a human edited it).
                                            An empty body is a corrupt/half-synced file.
    The present-body requirement here is the inverse of the deferred require-empty rule;
    `_check_kind_body` / `_check_score_body` also enforce the present body as the normal
    body check (since materialized states are not deferred), so the two are consistent."""
    errors: list[str] = []
    src = g.get("_body_source")
    if src is None:
        return errors
    if src not in VALID_BODY_SOURCES:
        errors.append(f"_body_source must be one of {sorted(VALID_BODY_SOURCES)}; got {src!r}")
        return errors
    if kind not in BODY_DEFERRABLE_KINDS:
        errors.append(f"_body_source is only valid for kind in {sorted(BODY_DEFERRABLE_KINDS)}; "
                      f"got kind={kind!r}")
        return errors
    if src == "platform":
        # DEFERRED: the platform authors the body on import, so it MUST be empty.
        if _is_nonempty_str(g.get("judge_prompt")):
            errors.append("_body_source=platform defers the body to the platform, so judge_prompt "
                          "must be omitted/empty (the author did not actually defer it)")
        if _is_nonempty_str(g.get("rubric")):
            errors.append("_body_source=platform defers the body to the platform, so rubric "
                          "must be omitted/empty (the author did not actually defer it)")
    else:
        # MATERIALIZED / HUMAN-EDITED: the body lives in-repo and MUST be present.
        if not _is_nonempty_str(g.get("judge_prompt")):
            errors.append(f"_body_source={src} requires a present (non-empty) judge_prompt — a "
                          f"materialized/human body must carry the body in-repo (empty = corrupt sync)")
        if kind == "llm_judge" and not _is_nonempty_str(g.get("rubric")):
            errors.append(f"_body_source={src} requires a present (non-empty) rubric for kind=llm_judge "
                          f"(empty = corrupt sync)")
    return errors


def _check_kind_body(g: Grader, kind: str | None) -> list[str]:
    errors: list[str] = []
    if kind == "llm_judge":
        # v8/v9: when the body is platform-DEFERRED (_body_source == "platform") the
        # author emits only the definition (the platform expands judge_prompt/rubric on
        # import), so skip the non-empty body checks — _check_body_source already verified
        # the body is actually empty. For every other state (legacy inline, AND the v9
        # materialized/human states, which are not deferred) the body MUST be present, so
        # this non-empty check runs and enforces it.
        if not _is_platform_deferred(g):
            if not _is_nonempty_str(g.get("judge_prompt")):
                errors.append("kind=llm_judge requires non-empty judge_prompt")
            if not _is_nonempty_str(g.get("rubric")):
                errors.append("kind=llm_judge requires non-empty rubric")
    elif kind == "deterministic":
        if not _is_nonempty_str(g.get("deterministic_check")):
            errors.append("kind=deterministic requires non-empty deterministic_check")
    elif kind == "execution":
        if not _is_nonempty_str(g.get("execution_spec")):
            errors.append("kind=execution requires non-empty execution_spec")
    elif kind == "agentic":
        errors += _check_agentic_body(g)
    return errors


def _check_agentic_body(g: Grader) -> list[str]:
    """kind=agentic carries an agent_spec the runner executes (sandbox + opencode).
    The plugin only validates the spec's shape — it never runs it."""
    errors: list[str] = []
    spec = g.get("agent_spec")
    if not isinstance(spec, dict) or not spec:
        errors.append("kind=agentic requires a non-empty agent_spec mapping")
        return errors
    if spec.get("harness") != "opencode":
        errors.append(f"agent_spec.harness must be 'opencode', got {spec.get('harness')!r}")
    sandbox = spec.get("sandbox")
    if not isinstance(sandbox, dict) or not _is_nonempty_str(sandbox.get("image")):
        errors.append("agent_spec.sandbox must be a mapping with a non-empty image")
    if not _is_nonempty_str(spec.get("task_prompt")):
        errors.append("agent_spec.task_prompt must be a non-empty string")
    if not _is_nonempty_str(spec.get("verdict_contract")):
        errors.append("agent_spec.verdict_contract must be a non-empty string")
    tools = spec.get("allowed_tools")
    if tools is not None and not isinstance(tools, list):
        errors.append("agent_spec.allowed_tools must be a list when present")
    return errors


def _check_applies_when(g: Grader) -> list[str]:
    """applies_when is free-form and ALWAYS LLM-evaluated at runtime (v6). For a
    deterministic grader the platform runs a separate LLM applicability gate before
    the gate-free deterministic_check; there is no code-evaluable mirror to author.

    `applies_when_check` (the v2 code-evaluable mirror, feature-removed in v6) is fully
    dropped from the contract in v9 — the validator no longer special-cases it. A stray
    key on a legacy file is now simply ignored (the schema has no additionalProperties
    constraint and the platform never read it)."""
    errors: list[str] = []
    raw = g.get("applies_when")
    if raw is not None and not isinstance(raw, str):
        errors.append("applies_when must be a string or null")
    return errors


def _check_id_shape(g: Grader, scope: str | None) -> list[str]:
    gid, fmid = g.get("id"), g.get("failure_mode_id")
    if not (_is_nonempty_str(gid) and _is_nonempty_str(fmid)):
        return []
    if scope in CALL_SITE_SCOPES and gid != f"{fmid}::grader":
        return [f"id should be '{fmid}::grader' for scope={scope}; got {gid!r}"]
    if scope == "chain":
        cid = g.get("chain_id")
        if _is_nonempty_str(cid) and not (gid.startswith(f"{cid}::") and gid.endswith("::grader")):
            return [f"id should match '{cid}::<failure_name>::grader' "
                    f"for scope=chain; got {gid!r}"]
    return []


def _check_meta(g: Grader) -> list[str]:
    """_meta is optional but, when present, must include required provenance fields."""
    meta = g.get("_meta")
    if meta is None:
        return []
    if not isinstance(meta, dict):
        return ["_meta must be a mapping"]
    errors: list[str] = []
    for key in ("author", "synthesized_at", "synth_inputs_digest"):
        if not _is_nonempty_str(meta.get(key)):
            errors.append(f"_meta.{key} must be a non-empty string when _meta is present")
    locked = meta.get("locked_fields")
    if locked is not None and not isinstance(locked, list):
        errors.append("_meta.locked_fields must be a list when present")
    human_edited = meta.get("human_edited")
    if human_edited is not None and not isinstance(human_edited, bool):
        errors.append("_meta.human_edited must be a boolean when present")
    # Grounding provenance: what the grader was written from. Optional and absent on pre-existing
    # bundles, so this is additive — but when present it must name a real state, never a guess.
    grounding = meta.get("grounding")
    if grounding is not None and grounding not in VALID_GROUNDING:
        errors.append(f"_meta.grounding must be one of {sorted(VALID_GROUNDING)} when present")
    # v9 body-lifecycle provenance — both optional, present only on materialized/human bodies.
    if meta.get("materialized_at") is not None and not _is_nonempty_str(meta.get("materialized_at")):
        errors.append("_meta.materialized_at must be a non-empty string when present")
    if meta.get("body_digest") is not None and not _is_nonempty_str(meta.get("body_digest")):
        errors.append("_meta.body_digest must be a non-empty string when present")
    errors += _check_body_digest(g, meta)
    return errors


def _canonical_body_digest(g: Grader) -> str:
    """Canonical SHA-256 over a grader's verdict body, used for v9 human-edit detection.

    Canonicalization (documented in grader.schema.json `_meta.body_digest`): take
    judge_prompt, then '\\n' + rubric when the rubric is present (llm_judge), strip
    trailing whitespace from every line, and strip leading/trailing blank lines — so
    trivial reformatting (a trailing space, an extra blank line) does not falsely read
    as a human edit, while any substantive change does."""
    parts = [g.get("judge_prompt") or ""]
    rubric = g.get("rubric")
    if _is_nonempty_str(rubric):
        parts.append(rubric)
    raw = "\n".join(parts)
    lines = [ln.rstrip() for ln in raw.split("\n")]
    while lines and lines[0] == "":
        lines.pop(0)
    while lines and lines[-1] == "":
        lines.pop()
    return _sha256_hex("\n".join(lines))


def _check_body_digest(g: Grader, meta: Mapping[str, Any]) -> list[str]:
    """v9 human-edit detection. A `platform-materialized` grader whose recomputed body
    digest no longer matches the recorded `_meta.body_digest` has been edited in-repo by
    a human. The file must then be PROMOTED to `_body_source: human` with
    `_meta.human_edited: true` so the next platform sync propagates the revision upstream.
    The validator surfaces this as an actionable error (the plugin ships no client tooling
    to mutate the file; the platform's GitHub-integration sync or a human applies the flip).
    A `human` grader whose digest is stale is fine — it is already marked, awaiting sync."""
    if g.get("_body_source") != "platform-materialized":
        return []
    recorded = meta.get("body_digest")
    if not _is_nonempty_str(recorded):
        return []
    if _canonical_body_digest(g) != recorded:
        return ["_body_source=platform-materialized but the body digest no longer matches "
                "_meta.body_digest — the materialized body was edited in-repo. Promote it to "
                "_body_source: human and set _meta.human_edited: true so the edit syncs back "
                "upstream (the platform's GitHub sync or a curator applies this)."]
    return []


def _check_pipeline_refs(
    g: Grader,
    pipeline: Pipeline,
    scope: str | None,
) -> list[str]:
    """Cross-reference grader IDs against the pipeline manifest."""
    errors: list[str] = []
    fm_ids = {fm.get("id") for fm in pipeline.get("failure_modes") or []
              if isinstance(fm, dict)}
    cs_ids = {cs.get("id") for cs in pipeline.get("call_sites") or []
              if isinstance(cs, dict)}
    chains_by_id: dict[Any, Mapping[str, Any]] = {
        c.get("id"): c for c in pipeline.get("chains") or [] if isinstance(c, dict)
    }

    if g.get("kind") == "score":
        qd_ids = {qd.get("id") for qd in pipeline.get("quality_dimensions") or []
                  if isinstance(qd, dict)}
        qdid = g.get("quality_dimension_id")
        if qdid not in qd_ids:
            errors.append(f"quality_dimension_id {qdid!r} not found in "
                          f"pipeline.quality_dimensions")
    else:
        fmid = g.get("failure_mode_id")
        if fmid not in fm_ids:
            errors.append(f"failure_mode_id {fmid!r} not found in pipeline.failure_modes")

    if scope in CALL_SITE_SCOPES and g.get("call_site_id") not in cs_ids:
        errors.append(f"call_site_id {g.get('call_site_id')!r} "
                      f"not found in pipeline.call_sites")

    if scope == "chain":
        cid = g.get("chain_id")
        if cid not in chains_by_id:
            errors.append(f"chain_id {cid!r} not found in pipeline.chains")
    return errors


# ----------------------------------------------------------------------------
# Top-level per-file validator.
# ----------------------------------------------------------------------------

def _score_scale(g: Grader) -> tuple[int, int] | None:
    sc = g.get("score_scale")
    if not isinstance(sc, dict):
        return None
    lo, hi = sc.get("min"), sc.get("max")
    if not isinstance(lo, int) or not isinstance(hi, int) or isinstance(lo, bool) or isinstance(hi, bool):
        return None
    return (lo, hi)


def _check_score_body(g: Grader) -> list[str]:
    errors: list[str] = []
    # v8: a platform-DEFERRED score grader emits the definition (rubric_levels +
    # score_scale) but not the judge_prompt — the platform expands it on import.
    # _check_body_source already verified no stray judge_prompt is carried. v9: the
    # materialized/human states are not deferred, so this present-judge_prompt check
    # runs for them too (a materialized score body must carry its judge_prompt).
    if not _is_platform_deferred(g) and not _is_nonempty_str(g.get("judge_prompt")):
        errors.append("kind=score requires non-empty judge_prompt")
    scale = _score_scale(g)
    if scale is None:
        errors.append("kind=score requires score_scale with integer min and max")
    elif scale[0] >= scale[1]:
        errors.append(f"score_scale.min ({scale[0]}) must be < max ({scale[1]})")
    rl = g.get("rubric_levels")
    if not isinstance(rl, dict) or not rl:
        errors.append("kind=score requires a non-empty rubric_levels mapping")
    elif scale is not None:
        expected = {str(n) for n in range(scale[0], scale[1] + 1)}
        got = {str(k) for k in rl}
        if got != expected:
            errors.append(f"rubric_levels keys must be {sorted(expected)}; got {sorted(got)}")
        for k, v in rl.items():
            if not _is_nonempty_str(v):
                errors.append(f"rubric_levels[{k!r}] must be a non-empty anchor description")
    return errors


def _check_id_shape_score(g: Grader) -> list[str]:
    gid, qdid = g.get("id"), g.get("quality_dimension_id")
    if not (_is_nonempty_str(gid) and _is_nonempty_str(qdid)):
        return []
    if gid != f"{qdid}::grader":
        return [f"id should be '{qdid}::grader' for kind=score; got {gid!r}"]
    return []


def _validate_score_grader(g: Grader, pipeline: Pipeline | None) -> list[str]:
    errors: list[str] = []
    errors += [f"missing or empty required field: {k}"
               for k in REQUIRED_SCALAR_FIELDS_SCORE if not _is_nonempty_str(g.get(k))]
    errors += _check_enums(g)
    scope = g.get("scope") if g.get("scope") in VALID_SCOPES else None
    errors += _check_scope_routing(g, scope)
    errors += _check_body_source(g, "score")
    errors += _check_score_body(g)
    errors += _check_meta(g)
    if g.get("applies_when") not in (None, ""):
        errors.append("kind=score must not set applies_when (a score grader always applies)")

    errors += _check_id_shape_score(g)
    if pipeline is not None:
        errors += _check_pipeline_refs(g, pipeline, scope)
    return errors


def validate_grader(g: Grader, pipeline: Pipeline | None = None) -> list[str]:
    # Accepted-broken short-circuit. See contract § "Retry semantics".
    if _is_nonempty_str(g.get("_validation_error")):
        return []

    if g.get("kind") == "score":
        return _validate_score_grader(g, pipeline)

    errors: list[str] = []
    errors += _check_required_fields(g)
    errors += _check_enums(g)

    scope = g.get("scope") if g.get("scope") in VALID_SCOPES else None
    kind = g.get("kind") if g.get("kind") in VALID_KINDS else None

    errors += _check_scope_routing(g, scope)
    errors += _check_body_source(g, kind)
    errors += _check_kind_body(g, kind)
    errors += _check_applies_when(g)
    errors += _check_meta(g)
    errors += _check_id_shape(g, scope)

    if pipeline is not None:
        errors += _check_pipeline_refs(g, pipeline, scope)

    return errors


# ----------------------------------------------------------------------------
# Bundle-level checks. Each takes loaded data, returns list[str] of errors.
# ----------------------------------------------------------------------------

def _bundle_fm_grader_bijection(
    pipeline: Pipeline,
    graders_by_id: Mapping[str, Grader],
    partial: bool = False,
) -> list[str]:
    errors: list[str] = []
    fms = pipeline.get("failure_modes") or []
    expected_grader_ids: set[str] = set()
    for fm in fms:
        if not isinstance(fm, dict):
            continue
        if partial and fm.get("grader_deferred") is True:
            continue
        gid = fm.get("grader_id")
        if not _is_nonempty_str(gid):
            errors.append(f"failure_mode {fm.get('id')!r} is missing grader_id")
            continue
        expected_grader_ids.add(gid)

    # Only failure-catching graders participate in the FM bijection; score
    # graders are matched against quality dimensions separately.
    actual = {gid for gid, g in graders_by_id.items() if g.get("kind") != "score"}
    missing = expected_grader_ids - actual
    orphan = actual - expected_grader_ids
    for gid in sorted(missing):
        errors.append(f"pipeline references grader_id {gid!r} but no grader file exists")
    for gid in sorted(orphan):
        errors.append(f"grader file {gid!r} has no matching failure_mode.grader_id in pipeline")
    return errors


def _bundle_qd_grader_bijection(
    pipeline: Pipeline,
    graders_by_id: Mapping[str, Grader],
) -> list[str]:
    """Quality dimensions <-> score graders. Quality dimensions are always graded
    in the first sweep (never deferred), so this is enforced in full and partial mode."""
    errors: list[str] = []
    expected: set[str] = set()
    for qd in pipeline.get("quality_dimensions") or []:
        if not isinstance(qd, dict):
            continue
        gid = qd.get("grader_id")
        if not _is_nonempty_str(gid):
            errors.append(f"quality_dimension {qd.get('id')!r} is missing grader_id")
            continue
        expected.add(gid)
    actual = {gid for gid, g in graders_by_id.items() if g.get("kind") == "score"}
    for gid in sorted(expected - actual):
        errors.append(f"quality_dimension references grader_id {gid!r} but no score "
                      f"grader file exists")
    for gid in sorted(actual - expected):
        errors.append(f"score grader {gid!r} has no matching "
                      f"quality_dimension.grader_id in pipeline")
    return errors


# Shapes whose output involves judgment — must carry >= 1 quality dimension.
JUDGMENT_SHAPES: Final[frozenset[str]] = frozenset({
    "agent_step", "route", "rag_answer", "classify", "draft", "rerank",
    "summarize", "conversational_turn",
})

# How the model is reached (call-site `invocation`); absent means in-process SDK.
VALID_INVOCATIONS: Final[frozenset[str]] = frozenset({
    "sdk", "cli_agent", "http", "sandbox_agent",
})

# How a call site's turns are grouped for grading (call-site `default_grade_mode`,
# schema 0.11.0); absent means per_turn.
VALID_GRADE_MODES: Final[frozenset[str]] = frozenset({
    "per_turn", "per_conversation",
})

# Telemetry-nomenclature matchers a call site may declare under `expected_spans`
# (schema 0.12.0). `match_field` selects what the instrumentation emits; metadata
# keys are addressed as the literal prefix `metadata.` + an arbitrary key.
VALID_EXPECTED_SPAN_FIELDS: Final[frozenset[str]] = frozenset({
    "name", "model", "trace_id",
})
VALID_EXPECTED_SPAN_KINDS: Final[frozenset[str]] = frozenset({"span", "trace"})
VALID_EXPECTED_SPAN_CONFIDENCE: Final[frozenset[str]] = frozenset({"high", "medium", "low"})
# v9 provenance. `observed` = the span name was read from real OTel/trace telemetry (a
# verified fact); `inferred` = guessed from static source (the v8 best-effort path).
# Absent defaults to `inferred`, so every legacy (v8) entry validates unchanged.
VALID_EXPECTED_SPAN_SOURCE: Final[frozenset[str]] = frozenset({"observed", "inferred"})


def _bundle_invocation_enum(pipeline: Pipeline) -> list[str]:
    """If a call site declares `invocation`, it must be a known kind.

    Absent is allowed and treated as `sdk` (the only value pre-0.9.0)."""
    errors: list[str] = []
    for cs in pipeline.get("call_sites") or []:
        if not isinstance(cs, dict):
            continue
        inv = cs.get("invocation")
        if inv is not None and inv not in VALID_INVOCATIONS:
            errors.append(f"call_site {cs.get('id')!r} has invalid invocation "
                          f"{inv!r}; expected one of {sorted(VALID_INVOCATIONS)}")
    return errors


def _bundle_grade_mode_enum(pipeline: Pipeline) -> list[str]:
    """If a call site declares `default_grade_mode`, it must be a known value.

    Absent is allowed and treated as `per_turn` (the pre-0.11.0 behavior).
    `per_conversation` marks a multi-turn site whose graders should be `scope: trace`."""
    errors: list[str] = []
    for cs in pipeline.get("call_sites") or []:
        if not isinstance(cs, dict):
            continue
        mode = cs.get("default_grade_mode")
        if mode is not None and mode not in VALID_GRADE_MODES:
            errors.append(f"call_site {cs.get('id')!r} has invalid default_grade_mode "
                          f"{mode!r}; expected one of {sorted(VALID_GRADE_MODES)}")
    return errors


VALID_CAPABILITY_KINDS: Final[frozenset[str]] = frozenset(
    {"tool", "skill", "mcp_server", "subagent"})


def _bundle_code_facts(pipeline: Pipeline) -> list[str]:
    """Validate the CODE-TRACKED FACTS (schema 0.15.0): a call site's `output_schema` and
    `tools`, and the product-level `capabilities` manifest.

    These describe the product's SOURCE rather than its traffic, so the platform reads them
    from the repo and keeps them in sync as the code changes. All three are OPTIONAL, and all
    three distinguish ABSENT from EMPTY — omitting `output_schema` means "this shard does not
    carry the fact" (the platform keeps whatever it captured), while an explicit `null` asserts
    the code declares no structured output. So `output_schema: null` must NOT be an error.

    Validation is deliberately shallow: `output_schema` and `input_schema` are arbitrary JSON
    Schemas whose contents are the user's business, and the platform already treats a schema it
    cannot compile as "no schema". What is checked is the shape the platform's parser depends on
    — that a mapping is a mapping and a list is a list — because those are what silently drop
    fields on import rather than failing loudly.
    """
    errors: list[str] = []
    for cs in pipeline.get("call_sites") or []:
        if not isinstance(cs, dict):
            continue
        cid = cs.get("id")

        schema = cs.get("output_schema")
        if schema is not None and not isinstance(schema, dict):
            errors.append(f"call_site {cid!r} output_schema must be a JSON Schema mapping "
                          f"(or null to assert the code declares none), got {type(schema).__name__}")

        tools = cs.get("tools")
        if tools is not None:
            if not isinstance(tools, list):
                errors.append(f"call_site {cid!r} tools must be a list when present")
            else:
                for i, tool in enumerate(tools):
                    where = f"call_site {cid!r} tools[{i}]"
                    if not isinstance(tool, dict):
                        errors.append(f"{where} must be a mapping")
                        continue
                    # `name` is the ONLY required field: a tool we can name but not fully
                    # specify is still worth declaring, and requiring more would silently drop
                    # exactly the tools we understand least.
                    if not _is_nonempty_str(tool.get("name")):
                        errors.append(f"{where} requires a non-empty name")
                    isch = tool.get("input_schema")
                    if isch is not None and not isinstance(isch, dict):
                        errors.append(f"{where} input_schema must be a JSON Schema mapping when present")

    caps = pipeline.get("capabilities")
    if caps is not None:
        if not isinstance(caps, list):
            errors.append("capabilities must be a list when present")
        else:
            for i, cap in enumerate(caps):
                where = f"capabilities[{i}]"
                if not isinstance(cap, dict):
                    errors.append(f"{where} must be a mapping")
                    continue
                if not _is_nonempty_str(cap.get("name")):
                    errors.append(f"{where} requires a non-empty name")
                # `kind` is an OPEN vocabulary, matching the platform (`model/Capability.kind` is a
                # plain String; its @Schema annotation is documentation, not a constraint). It must not
                # be rejected here: the platform's observer is the intended WRITER of this manifest, so
                # hard-failing an unrecognized kind would make the plugin reject a bundle the platform
                # itself just produced — the validator would be the thing breaking the contract. A
                # non-string kind is still wrong, because that is a shape error rather than a
                # vocabulary one, and absent is legal (it defaults to `tool` platform-side).
                kind = cap.get("kind")
                if kind is not None and not isinstance(kind, str):
                    errors.append(f"{where} kind must be a string when present, got {type(kind).__name__}")
                ids = cap.get("call_site_ids")
                if ids is not None and not isinstance(ids, list):
                    errors.append(f"{where} call_site_ids must be a list when present")
    return errors


def _bundle_expected_spans(pipeline: Pipeline) -> list[str]:
    """If a call site declares `expected_spans` (schema 0.12.0), validate each entry.

    `expected_spans` is an OPTIONAL, best-effort, orchestrator-owned list of telemetry
    matchers extracted from the call site's code (OTel span names, Langfuse names,
    enclosing function name, etc.) so the platform can bind graders to the right
    captured spans/traces. Absent / empty is always allowed. Each entry carries:
      match_field   one of name | model | trace_id | metadata.<key>
      match_pattern non-empty string (exact or glob)
      kind          span | trace
      source        observed | inferred  (v9; absent ⇒ inferred — legacy entries pass)
      confidence    high | medium | low  (REQUIRED for inferred entries; OPTIONAL/moot
                                          for observed entries — an observed span name is
                                          a verified fact, not a guess)
    v9: an `observed` entry was read from real telemetry, so the platform trusts it
    unconditionally and only ranks `inferred` entries by `confidence`.
    """
    errors: list[str] = []
    for cs in pipeline.get("call_sites") or []:
        if not isinstance(cs, dict):
            continue
        spans = cs.get("expected_spans")
        if spans is None:
            continue
        cid = cs.get("id")
        if not isinstance(spans, list):
            errors.append(f"call_site {cid!r} expected_spans must be a list when present")
            continue
        for i, entry in enumerate(spans):
            where = f"call_site {cid!r} expected_spans[{i}]"
            if not isinstance(entry, dict):
                errors.append(f"{where} must be a mapping")
                continue
            mf = entry.get("match_field")
            if not _is_valid_match_field(mf):
                errors.append(f"{where} has invalid match_field {mf!r}; expected one of "
                              f"{sorted(VALID_EXPECTED_SPAN_FIELDS)} or 'metadata.<key>'")
            if not _is_nonempty_str(entry.get("match_pattern")):
                errors.append(f"{where} requires a non-empty match_pattern")
            kind = entry.get("kind")
            if kind not in VALID_EXPECTED_SPAN_KINDS:
                errors.append(f"{where} has invalid kind {kind!r}; expected one of "
                              f"{sorted(VALID_EXPECTED_SPAN_KINDS)}")
            # v9 source provenance — absent defaults to inferred (legacy behavior).
            source = entry.get("source")
            if source is not None and source not in VALID_EXPECTED_SPAN_SOURCE:
                errors.append(f"{where} has invalid source {source!r}; expected one of "
                              f"{sorted(VALID_EXPECTED_SPAN_SOURCE)}")
            is_observed = source == "observed"
            conf = entry.get("confidence")
            # confidence is moot for an observed (verified) entry, so it is optional there;
            # for inferred (the default, incl. all legacy entries) it stays required.
            if conf is None and is_observed:
                pass
            elif conf not in VALID_EXPECTED_SPAN_CONFIDENCE:
                hint = (" (optional for source=observed)" if is_observed else
                        " (required for inferred entries)")
                errors.append(f"{where} has invalid confidence {conf!r}; expected one of "
                              f"{sorted(VALID_EXPECTED_SPAN_CONFIDENCE)}{hint}")
    return errors


def _is_valid_match_field(value: Any) -> bool:
    """`match_field` is one of the fixed scalar fields or a `metadata.<key>` selector."""
    if value in VALID_EXPECTED_SPAN_FIELDS:
        return True
    return (
        isinstance(value, str)
        and value.startswith("metadata.")
        and value[len("metadata."):].strip() != ""
    )


def _bundle_quality_coverage(pipeline: Pipeline) -> list[str]:
    """Every judgment-shape call site must have at least one quality dimension.

    This is the never-skipped guarantee: the gap the synthesizer used to leave
    (no quality/grey-area evals at all) is now a hard error."""
    errors: list[str] = []
    qd_sites = {qd.get("call_site_id") for qd in pipeline.get("quality_dimensions") or []
                if isinstance(qd, dict)}
    for cs in pipeline.get("call_sites") or []:
        if not isinstance(cs, dict):
            continue
        if cs.get("shape") in JUDGMENT_SHAPES and cs.get("id") not in qd_sites:
            errors.append(f"call_site {cs.get('id')!r} (shape={cs.get('shape')!r}) has no "
                          f"quality dimensions — judgment call sites must be scored on "
                          f"at least one quality axis")
    return errors


def _bundle_duplicate_ids(graders: Sequence[tuple[Path, Grader]]) -> list[str]:
    errors: list[str] = []
    seen: dict[str, Path] = {}
    for path, g in graders:
        gid = g.get("id")
        if not _is_nonempty_str(gid):
            continue
        if gid in seen:
            errors.append(f"duplicate grader id {gid!r}: {seen[gid]} and {path}")
        else:
            seen[gid] = path
    return errors


def _bundle_taxonomy_assigned(
    graders: Sequence[tuple[Path, Grader]], pipeline: Pipeline
) -> list[str]:
    """Non-partial only: every failure-catching grader must resolve to a non-empty
    `taxonomy_node_id` by the final gate. Mid-run (partial) the taxonomy is not yet
    built (Phase D), so this is skipped there — see REQUIRED_SCALAR_FIELDS note.

    Single_call graders are emitted at Phase C (before taxonomy clustering at D), so
    they carry `taxonomy_node_id: ""`; Phase D patches the *failure-mode entry* shards,
    not the graders. We therefore resolve a grader's taxonomy through its
    `failure_mode_id` → the FM entry's `taxonomy_node_id` (which D.4 fills reliably).
    A value spliced directly onto the grader (chain graders, post-taxonomy at D.5) is
    honored too. A grader is taxonomy-assigned iff either it or its FM entry has a
    non-empty `taxonomy_node_id`. Score graders are exempt."""
    fm_taxonomy: dict[str, Any] = {
        fm.get("id"): fm.get("taxonomy_node_id")
        for fm in pipeline.get("failure_modes") or []
        if isinstance(fm, dict) and _is_nonempty_str(fm.get("id"))
    }
    errors: list[str] = []
    for path, g in graders:
        if g.get("kind") == "score":
            continue
        if _is_nonempty_str(g.get("_validation_error")):
            continue
        if _is_nonempty_str(g.get("taxonomy_node_id")):
            continue
        if _is_nonempty_str(fm_taxonomy.get(g.get("failure_mode_id"))):
            continue
        errors.append(
            f"{path}: failure-catching grader has no taxonomy_node_id and its "
            f"failure_mode_id {g.get('failure_mode_id')!r} resolves to no FM entry "
            f"with a non-empty taxonomy_node_id"
        )
    return errors


def _bundle_taxonomy_reachability(pipeline: Pipeline) -> list[str]:
    errors: list[str] = []
    tax = pipeline.get("taxonomy") or []
    tax_ids = {t.get("id") for t in tax if isinstance(t, dict) and _is_nonempty_str(t.get("id"))}
    fm_node_ids: set[str] = set()
    fms = pipeline.get("failure_modes") or []
    for fm in fms:
        if not isinstance(fm, dict):
            continue
        node = fm.get("taxonomy_node_id")
        if _is_nonempty_str(node):
            fm_node_ids.add(node)
            if node not in tax_ids:
                errors.append(f"failure_mode {fm.get('id')!r} references "
                              f"unknown taxonomy_node_id {node!r}")
    orphan_nodes = tax_ids - fm_node_ids - {None}
    # Parent nodes are allowed to be empty if children exist; check that
    parent_ids = {t.get("parent_id") for t in tax if isinstance(t, dict)}
    truly_orphan = {n for n in orphan_nodes if n not in parent_ids}
    for node in sorted(truly_orphan):
        errors.append(f"taxonomy node {node!r} has no failure_modes and no children — "
                      f"either populate or remove")
    return errors


def _bundle_chain_acyclic(pipeline: Pipeline) -> list[str]:
    """Detect cycles in chain.call_site_ids ordering across chains.

    A chain that lists [A, B, A] is a cycle within itself. A pair of chains
    where chain1=[A,B] and chain2=[B,A] is a cross-chain cycle when one
    chain's last call site appears before the first call site of another
    chain that loops back.
    """
    errors: list[str] = []
    chains = pipeline.get("chains") or []

    # Internal cycle check
    for c in chains:
        if not isinstance(c, dict):
            continue
        sites = c.get("call_site_ids") or []
        if len(sites) != len(set(sites)):
            counts = Counter(sites)
            # ensemble is allowed to repeat the same id; differentiate by detection_method
            if c.get("detection_method") != "ensemble":
                dupes = [s for s, n in counts.items() if n > 1]
                errors.append(f"chain {c.get('id')!r} repeats call_site_ids {dupes!r} "
                              f"without detection_method=ensemble")

    # Cross-chain DAG check
    graph: dict[str, set[str]] = defaultdict(set)
    for c in chains:
        if not isinstance(c, dict):
            continue
        sites = c.get("call_site_ids") or []
        for a, b in zip(sites, sites[1:]):
            graph[a].add(b)

    # Tarjan-lite cycle detection
    WHITE, GRAY, BLACK = 0, 1, 2
    color: dict[str, int] = defaultdict(lambda: WHITE)

    def visit(node: str, path: list[str]) -> str | None:
        if color[node] == GRAY:
            cycle_start = path.index(node)
            return " -> ".join(path[cycle_start:] + [node])
        if color[node] == BLACK:
            return None
        color[node] = GRAY
        path.append(node)
        for nxt in graph.get(node, ()):
            found = visit(nxt, path)
            if found:
                return found
        path.pop()
        color[node] = BLACK
        return None

    for start in list(graph.keys()):
        if color[start] == WHITE:
            cycle = visit(start, [])
            if cycle:
                errors.append(f"chains contain a cycle: {cycle}")
                break
    return errors


def _bundle_pack_resolution(pipeline: Pipeline, graders: Sequence[tuple[Path, Grader]]) -> list[str]:
    """Every pack_id on a failure mode or grader must resolve to pipeline.packs[].id."""
    errors: list[str] = []
    declared = {p.get("id") for p in pipeline.get("packs") or [] if isinstance(p, dict)}
    declared.discard(None)
    for fm in pipeline.get("failure_modes") or []:
        if not isinstance(fm, dict):
            continue
        for pid in fm.get("pack_ids") or []:
            if pid not in declared:
                errors.append(f"failure_mode {fm.get('id')!r} carries pack_id "
                              f"{pid!r} not in pipeline.packs[]")
    for path, g in graders:
        for pid in g.get("pack_ids") or []:
            if pid not in declared:
                errors.append(f"{path}: pack_id {pid!r} not in pipeline.packs[]")
    return errors


def _bundle_dedup_uniqueness(pipeline: Pipeline) -> list[str]:
    """After step 4.6 dedup, no two failures may share (scope, call_site|chain, name)."""
    errors: list[str] = []
    seen: dict[tuple[str, str, str], str] = {}
    for fm in pipeline.get("failure_modes") or []:
        if not isinstance(fm, dict):
            continue
        scope = fm.get("scope") or ""
        site_or_chain = fm.get("call_site_id") if scope == "single_call" else fm.get("chain_id")
        if not _is_nonempty_str(site_or_chain):
            continue
        name = fm.get("name") or ""
        key = (scope, site_or_chain, name)
        if key in seen:
            errors.append(f"dedup invariant violated: failure_modes {seen[key]!r} and "
                          f"{fm.get('id')!r} share ({scope}, {site_or_chain}, {name}) — "
                          f"step 4.6 should have merged or conflict-suffixed")
        else:
            seen[key] = fm.get("id") or ""
    return errors


def _bundle_pack_dependencies(pipeline: Pipeline) -> list[str]:
    """Pack dependencies satisfied; conflicts not co-engaged.

    Reads pack manifests from the live pack registry if available; otherwise
    relies on dependency/conflict lists embedded in pipeline.packs[] entries
    (which the orchestrator records at step 0.5).
    """
    errors: list[str] = []
    packs = pipeline.get("packs") or []
    declared = {p.get("id") for p in packs if isinstance(p, dict)}
    for p in packs:
        if not isinstance(p, dict):
            continue
        pid = p.get("id") or ""
        for dep in p.get("dependencies") or []:
            if dep not in declared:
                errors.append(f"pack {pid!r} declares dependency on {dep!r} "
                              f"but that pack is not engaged")
        for conf in p.get("conflicts") or []:
            if conf in declared:
                errors.append(f"pack {pid!r} declares conflict with {conf!r} "
                              f"but both are engaged")
    return errors


def _bundle_coverage_gates(pipeline: Pipeline) -> list[str]:
    """Enforce step 4.6 gates per call site / chain."""
    errors: list[str] = []
    call_sites = {cs.get("id"): cs for cs in pipeline.get("call_sites") or []
                  if isinstance(cs, dict)}
    chains = {c.get("id"): c for c in pipeline.get("chains") or [] if isinstance(c, dict)}
    fms = pipeline.get("failure_modes") or []

    per_site_layer: dict[str, Counter[str]] = defaultdict(Counter)
    per_chain_count: Counter[str] = Counter()
    for fm in fms:
        if not isinstance(fm, dict):
            continue
        scope = fm.get("scope")
        layer = fm.get("layer")
        if scope == "single_call":
            site = fm.get("call_site_id")
            if site:
                per_site_layer[site][layer or "?"] += 1
        elif scope == "chain":
            cid = fm.get("chain_id")
            if cid:
                per_chain_count[cid] += 1

    for site_id, cs in call_sites.items():
        layers = per_site_layer.get(site_id, Counter())
        shape = cs.get("shape")
        if layers["A"] < MIN_LAYER_A_PER_SITE and shape != "embedding":
            errors.append(f"call_site {site_id!r} has {layers['A']} Layer A failures; "
                          f"step 4.6 requires >= {MIN_LAYER_A_PER_SITE}")
        if layers["B"] < MIN_LAYER_B_PER_SITE and shape not in ("embedding",):
            errors.append(f"call_site {site_id!r} has {layers['B']} Layer B failures; "
                          f"step 4.6 requires >= {MIN_LAYER_B_PER_SITE}")
        if layers["C"] < MIN_LAYER_C_PER_SITE and shape != "embedding":
            errors.append(f"call_site {site_id!r} has {layers['C']} Layer C failures; "
                          f"step 4.6 requires >= {MIN_LAYER_C_PER_SITE}")

    for cid in chains:
        if per_chain_count[cid] < MIN_CHAIN_FAILURES:
            errors.append(f"chain {cid!r} has {per_chain_count[cid]} cross-call failures; "
                          f"step 4.6 requires >= {MIN_CHAIN_FAILURES}")
    return errors


# ----------------------------------------------------------------------------
# Lock file + calibration-set helpers.
# ----------------------------------------------------------------------------

def _sha256_hex(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def _check_lock_consistency(evals_dir: Path, lock: Mapping[str, Any]) -> list[str]:
    errors: list[str] = []
    locked_graders = lock.get("graders") or {}
    graders_dir = evals_dir / "graders"
    if not isinstance(locked_graders, dict):
        return [".tessary/.synth-lock.yaml graders: must be a mapping"]
    for safe_id, expected_hash in locked_graders.items():
        path = graders_dir / f"{safe_id}.yaml"
        if not path.is_file():
            errors.append(f".synth-lock.yaml references {safe_id}.yaml but file is missing")
            continue
        actual = _sha256_hex(path.read_text(encoding="utf-8"))
        if actual != expected_hash:
            # Diverged. Only an error if locked_fields is empty AND human_edited is false.
            try:
                grader = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
            except yaml.YAMLError:
                grader = {}
            meta = grader.get("_meta") or {}
            locked_fields = meta.get("locked_fields") or []
            human_edited = bool(meta.get("human_edited"))
            if not locked_fields and not human_edited:
                errors.append(f"{safe_id}.yaml diverged from .synth-lock.yaml without "
                              f"_meta.locked_fields or human_edited=true — "
                              f"pass --force to overwrite, or set _meta to preserve")
    return errors


def _run_calibration_set(
    csv_path: Path,
    graders_by_id: Mapping[str, Grader],
) -> list[str]:
    """Informational: read CSV (grader_id, sample_output, verdict), compare to grader.

    This validator can't run an LLM judge; we just report per-grader counts so the
    operator can wire up their own calibration runner.
    """
    notes: list[str] = []
    if not csv_path.is_file():
        return [f"--calibration-set: file not found: {csv_path}"]
    try:
        with csv_path.open(encoding="utf-8") as f:
            rows = list(csv.DictReader(f))
    except Exception as e:
        return [f"--calibration-set: cannot read CSV: {e}"]
    needed = {"grader_id", "sample_output", "verdict"}
    if rows and not needed.issubset(rows[0].keys()):
        return [f"--calibration-set: CSV must have columns {sorted(needed)}; "
                f"got {sorted(rows[0].keys())}"]
    counts: Counter[tuple[str, str]] = Counter()
    for r in rows:
        gid = (r.get("grader_id") or "").strip()
        v = (r.get("verdict") or "").strip()
        if gid and v in VALID_VERDICTS:
            counts[(gid, v)] += 1
    grader_ids = sorted({gid for gid, _ in counts})
    notes.append(f"calibration-set: {len(rows)} labelled rows across {len(grader_ids)} grader(s)")
    for gid in grader_ids:
        if gid not in graders_by_id:
            notes.append(f"  - {gid}: UNKNOWN grader id (skipped)")
            continue
        breakdown = {v: counts[(gid, v)] for v in sorted(VALID_VERDICTS)}
        notes.append(f"  - {gid}: {breakdown}")
    return notes


# ----------------------------------------------------------------------------
# IO + CLI.
# ----------------------------------------------------------------------------

def _load_yaml(path: Path, label: str) -> Any:
    try:
        return yaml.safe_load(path.read_text(encoding="utf-8"))
    except yaml.YAMLError as e:
        print(f"validate.py: YAML parse error in {label} ({path}): {e}", file=sys.stderr)
        sys.exit(1 if label == "grader" else 2)


def _run_per_file(grader_path: Path, pipeline_path: Path | None) -> int:
    grader = _load_yaml(grader_path, "grader")
    if not isinstance(grader, dict):
        print(f"validate.py: {grader_path}: must be a YAML mapping at the top level",
              file=sys.stderr)
        return 1

    pipeline: Mapping[str, Any] | None = None
    if pipeline_path:
        # --pipeline accepts a .tessary/ directory (preferred) for the v0.4 sharded
        # layout, or a legacy single pipeline.yaml file.
        if pipeline_path.is_dir():
            try:
                pipeline = pipeline_io.load_pipeline(pipeline_path)
            except RuntimeError as e:
                print(f"validate.py: {e}", file=sys.stderr)
                return 1
        elif pipeline_path.is_file():
            loaded = _load_yaml(pipeline_path, "pipeline")
            if not isinstance(loaded, dict):
                print(f"validate.py: {pipeline_path}: must be a YAML mapping at the top level",
                      file=sys.stderr)
                return 1
            pipeline = loaded
        else:
            print(f"validate.py: --pipeline path not found: {pipeline_path}", file=sys.stderr)
            return 2

    errors = validate_grader(grader, pipeline)
    if errors:
        for msg in errors:
            print(f"{grader_path}: {msg}", file=sys.stderr)
        return 1
    print(f"{grader_path}: OK")
    return 0


def _pack_filter_report(
    pipeline: Pipeline,
    graders_by_id: Mapping[str, Grader],
    pack_id: str,
) -> list[str]:
    """Print a coverage matrix for one pack: which failures / graders / compliance tags."""
    declared = {p.get("id") for p in pipeline.get("packs") or [] if isinstance(p, dict)}
    if pack_id not in declared:
        return [f"pack {pack_id!r} not engaged in this pipeline (declared packs: "
                f"{sorted(x for x in declared if x)})"]
    lines = [f"--pack {pack_id}: coverage matrix"]
    layer_counter: Counter[str] = Counter()
    tag_counter: Counter[str] = Counter()
    matched_grader_ids: set[str] = set()
    for fm in pipeline.get("failure_modes") or []:
        if not isinstance(fm, dict):
            continue
        if pack_id not in (fm.get("pack_ids") or []):
            continue
        layer_counter[fm.get("layer") or "?"] += 1
        for tag in fm.get("compliance_tags") or []:
            tag_counter[tag] += 1
        if _is_nonempty_str(fm.get("grader_id")):
            matched_grader_ids.add(fm["grader_id"])
    lines.append(f"  failures: {sum(layer_counter.values())} "
                 f"({dict(sorted(layer_counter.items()))})")
    lines.append(f"  graders: {len(matched_grader_ids)}")
    if tag_counter:
        lines.append(f"  compliance tags:")
        for tag, n in sorted(tag_counter.items()):
            lines.append(f"    {tag}: {n}")
    return lines


def _run_bundle(evals_dir: Path, calibration_csv: Path | None,
                pack_filter: str | None = None,
                partial: bool = False) -> int:
    if not evals_dir.is_dir():
        print(f"validate.py: --bundle path is not a directory: {evals_dir}", file=sys.stderr)
        return 2

    pipeline_root = evals_dir / "pipeline"
    if not pipeline_root.is_dir():
        print(f"validate.py: {pipeline_root}/ missing (v0.4 sharded layout)",
              file=sys.stderr)
        return 2
    try:
        pipeline = pipeline_io.load_pipeline(evals_dir)
    except RuntimeError as e:
        print(f"validate.py: {e}", file=sys.stderr)
        return 1
    if not isinstance(pipeline, dict):
        print(f"validate.py: {pipeline_root}: assembled pipeline view is not a mapping",
              file=sys.stderr)
        return 1

    graders_dir = evals_dir / "graders"
    graders: list[tuple[Path, Grader]] = []
    per_file_errors: list[str] = []
    if graders_dir.is_dir():
        for path in sorted(graders_dir.rglob("*.yaml")):
            g = _load_yaml(path, "grader")
            if not isinstance(g, dict):
                per_file_errors.append(f"{path}: must be a YAML mapping at the top level")
                continue
            graders.append((path, g))
            for msg in validate_grader(g, pipeline):
                per_file_errors.append(f"{path}: {msg}")
    elif not partial:
        print(f"validate.py: {graders_dir} missing", file=sys.stderr)
        return 2

    graders_by_id: dict[str, Grader] = {
        g.get("id"): g for _, g in graders if _is_nonempty_str(g.get("id"))
    }

    bundle_errors: list[str] = []
    bundle_errors += _bundle_fm_grader_bijection(pipeline, graders_by_id, partial=partial)
    bundle_errors += _bundle_qd_grader_bijection(pipeline, graders_by_id)
    bundle_errors += _bundle_quality_coverage(pipeline)
    bundle_errors += _bundle_invocation_enum(pipeline)
    bundle_errors += _bundle_grade_mode_enum(pipeline)
    bundle_errors += _bundle_expected_spans(pipeline)
    bundle_errors += _bundle_code_facts(pipeline)
    bundle_errors += _bundle_duplicate_ids(graders)
    bundle_errors += _bundle_taxonomy_reachability(pipeline)
    bundle_errors += _bundle_chain_acyclic(pipeline)
    bundle_errors += _bundle_pack_resolution(pipeline, graders)
    bundle_errors += _bundle_dedup_uniqueness(pipeline)
    bundle_errors += _bundle_pack_dependencies(pipeline)

    coverage_msgs = _bundle_coverage_gates(pipeline)
    if partial:
        bundle_warnings = list(coverage_msgs)
    else:
        bundle_errors += coverage_msgs
        # taxonomy_node_id is only available once Phase D has clustered; enforce its
        # presence on failure-catching graders only at the final non-partial gate.
        # Resolved via the grader's failure_mode_id → FM entry (D.4 patches entries).
        bundle_errors += _bundle_taxonomy_assigned(graders, pipeline)
        bundle_warnings = []

    lock_path = evals_dir / ".synth-lock.yaml"
    if lock_path.is_file():
        lock = _load_yaml(lock_path, "lock")
        if isinstance(lock, dict):
            bundle_errors += _check_lock_consistency(evals_dir, lock)

    notes: list[str] = []
    if calibration_csv is not None:
        notes += _run_calibration_set(calibration_csv, graders_by_id)
    if pack_filter:
        notes += _pack_filter_report(pipeline, graders_by_id, pack_filter)

    print(f"bundle: {len(graders)} grader file(s) under {graders_dir}")
    for line in notes:
        print(line)
    for warn in bundle_warnings:
        print(f"warn: {warn}", file=sys.stderr)

    all_errors = per_file_errors + bundle_errors
    if all_errors:
        for msg in all_errors:
            print(msg, file=sys.stderr)
        print(f"bundle: {len(all_errors)} error(s)", file=sys.stderr)
        return 1
    if partial:
        print(f"bundle: OK (partial; {len(bundle_warnings)} coverage warning(s))")
    else:
        print("bundle: OK")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description="Validate a grader YAML or a full .tessary/ bundle.")
    ap.add_argument("file", nargs="?", help="Path to a grader YAML file (per-file mode).")
    ap.add_argument("--pipeline", help="Optional path to .tessary/ (preferred) or a legacy "
                                       "pipeline.yaml file for per-file cross-checks.")
    ap.add_argument("--bundle", help="Path to a .tessary/ directory for full-bundle validation.")
    ap.add_argument("--calibration-set", help="Optional CSV (grader_id,sample_output,verdict) for "
                                              "informational agreement reporting.")
    ap.add_argument("--pack", help="Bundle mode only — narrow output to a single pack id and "
                                   "print a compliance-tag coverage matrix.")
    ap.add_argument("--partial", action="store_true",
                    help="Bundle mode — tolerate deferred failure modes (no grader file yet) "
                         "and downgrade per-site coverage shortfalls to warnings.")
    args = ap.parse_args()

    if args.bundle:
        if args.file or args.pipeline:
            print("validate.py: --bundle is mutually exclusive with the positional file / --pipeline",
                  file=sys.stderr)
            return 2
        evals_dir = Path(args.bundle).resolve()
        cal = Path(args.calibration_set).resolve() if args.calibration_set else None
        return _run_bundle(evals_dir, cal, args.pack, partial=args.partial)
    if args.pack:
        print("validate.py: --pack is only valid with --bundle", file=sys.stderr)
        return 2

    if not args.file:
        ap.print_usage(sys.stderr)
        return 2
    grader_path = Path(args.file).resolve()
    if not grader_path.is_file():
        print(f"validate.py: not a file: {grader_path}", file=sys.stderr)
        return 2
    pipeline_path = Path(args.pipeline).resolve() if args.pipeline else None
    return _run_per_file(grader_path, pipeline_path)


if __name__ == "__main__":
    sys.exit(main())
