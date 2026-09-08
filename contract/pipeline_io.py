#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""
pipeline_io.py — read/write shards under `.tessary/pipeline/`.

The v0.4 layout splits what was a single `.tessary/pipeline.yaml` into one shard
per logical artifact. This module is the single place that knows the on-disk
layout. Consumers (`validate.py`, `viewer.py`, `dedup.py`, `audit.py`,
`finalize.py`) read shards through `load_pipeline()`, which returns the
v0.3-compatible top-level mapping so existing logic stays unchanged.

Shard paths under `.tessary/pipeline/`:
    meta.yaml                       -> version, product_hint, runtime
    packs.yaml                      -> packs[]
    product_profile.yaml            -> product_profile
    invariants.yaml                 -> implicit_invariants, invariant_coverage
    chains.yaml                     -> chains[]
    taxonomy.yaml                   -> taxonomy[]
    capabilities.yaml               -> capabilities[]
    call_sites/<id_path>.yaml       -> one call_site mapping per file
    failure_modes/<id_path>.yaml    -> {failure_modes: [...]} per call site
    failure_modes/_chains.yaml      -> {failure_modes: [...]} for chain scope

Filenames nest the canonical `::`-delimited id as folders (`::` -> `/`), so a
call site `sha::a1b2` is written to `call_sites/sha/a1b2.yaml`. Grader files drop
the redundant trailing `::grader` and nest the rest (see `grader_rel_path`).
"""
from __future__ import annotations

import hashlib
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable, Mapping

try:
    import yaml
except ImportError:
    print("pipeline_io: requires PyYAML. Install: pip install pyyaml", file=sys.stderr)
    sys.exit(2)


# Filenames nest the canonical `::`-delimited id as folders (`::` -> `/`).
# Mirrors the grader-filename convention.
def id_path(ident: str) -> str:
    return ident.replace("::", "/")


# Grader files drop the redundant trailing `::grader` and nest the rest, so the
# grader `checkout::wrong_total::grader` is written to
# `graders/checkout/wrong_total.yaml`. The canonical `id:` inside the file keeps `::`.
GRADER_ID_SUFFIX = "::grader"


def grader_rel_path(grader_id: str) -> str:
    """Path relative to `.tessary/` for a grader given its canonical `::` id."""
    stem = grader_id
    if stem.endswith(GRADER_ID_SUFFIX):
        stem = stem[: -len(GRADER_ID_SUFFIX)]
    return f"graders/{id_path(stem)}.yaml"


def grader_lock_key(rel: str) -> str:
    """Lock key for a grader: its path under `graders/` minus the `.yaml` suffix.

    Keying by the nested path (not `Path(rel).stem`) keeps keys unique once
    grader filenames nest into folders. Accepts a rel path with or without the
    leading `graders/`.
    """
    key = rel[len("graders/"):] if rel.startswith("graders/") else rel
    if key.endswith(".yaml"):
        key = key[: -len(".yaml")]
    return key


def grader_id_from_rel_path(rel: str) -> str:
    """Inverse of `grader_rel_path`: canonical `::` grader id from its path."""
    return f"{grader_lock_key(rel).replace('/', '::')}{GRADER_ID_SUFFIX}"


def pipeline_dir(evals_dir: Path) -> Path:
    # Coerce str → Path so every writer/reader entry point is str-tolerant
    # (idempotent for existing Path callers).
    return Path(evals_dir) / "pipeline"


def _load_yaml(path: Path) -> Any:
    if not path.is_file():
        return None
    try:
        return yaml.safe_load(path.read_text(encoding="utf-8"))
    except yaml.YAMLError as e:
        raise RuntimeError(f"YAML parse error in {path}: {e}") from e


def _expect_mapping(value: Any, path: Path) -> dict[str, Any]:
    if value is None:
        return {}
    if not isinstance(value, dict):
        raise RuntimeError(f"{path}: expected a YAML mapping at the top level")
    return value


def _expect_list(value: Any, key: str, path: Path) -> list[Any]:
    if value is None:
        return []
    if not isinstance(value, list):
        raise RuntimeError(f"{path}: {key!r} must be a list")
    return value


def load_pipeline(evals_dir: Path) -> dict[str, Any]:
    """Assemble the v0.3-compatible pipeline mapping from shards under .tessary/pipeline/.

    Missing shards are tolerated (treated as empty). Malformed YAML or wrong
    top-level shape raises RuntimeError so the caller can surface a clean error.
    """
    p = pipeline_dir(evals_dir)
    out: dict[str, Any] = {
        "version": None,
        "product_hint": None,
        "packs": [],
        "product_profile": None,
        "implicit_invariants": [],
        "invariant_coverage": [],
        "runtime": {},
        "progress": {},
        "call_sites": [],
        "chains": [],
        "failure_modes": [],
        "quality_dimensions": [],
        "taxonomy": [],
        "capabilities": [],
    }

    meta = _expect_mapping(_load_yaml(p / "meta.yaml"), p / "meta.yaml")
    if meta:
        out["version"] = meta.get("version")
        out["product_hint"] = meta.get("product_hint")
        runtime = meta.get("runtime")
        out["runtime"] = runtime if isinstance(runtime, dict) else {}
        progress = meta.get("progress")
        out["progress"] = progress if isinstance(progress, dict) else {}

    packs_doc = _expect_mapping(_load_yaml(p / "packs.yaml"), p / "packs.yaml")
    out["packs"] = _expect_list(packs_doc.get("packs"), "packs", p / "packs.yaml")

    profile_doc = _expect_mapping(
        _load_yaml(p / "product_profile.yaml"), p / "product_profile.yaml"
    )
    out["product_profile"] = profile_doc.get("product_profile")

    invariants_doc = _expect_mapping(
        _load_yaml(p / "invariants.yaml"), p / "invariants.yaml"
    )
    out["implicit_invariants"] = _expect_list(
        invariants_doc.get("implicit_invariants"), "implicit_invariants",
        p / "invariants.yaml",
    )
    out["invariant_coverage"] = _expect_list(
        invariants_doc.get("invariant_coverage"), "invariant_coverage",
        p / "invariants.yaml",
    )

    chains_doc = _expect_mapping(_load_yaml(p / "chains.yaml"), p / "chains.yaml")
    out["chains"] = _expect_list(chains_doc.get("chains"), "chains", p / "chains.yaml")

    # capabilities.yaml (schema 0.15.0) — the product's tool/skill/MCP/subagent inventory read out
    # of the CODE. Optional like every other shard here: a bundle without it declares no inventory,
    # which is NOT the same as declaring it has none.
    caps_doc = _expect_mapping(_load_yaml(p / "capabilities.yaml"), p / "capabilities.yaml")
    out["capabilities"] = _expect_list(
        caps_doc.get("capabilities"), "capabilities", p / "capabilities.yaml"
    )

    taxonomy_doc = _expect_mapping(_load_yaml(p / "taxonomy.yaml"), p / "taxonomy.yaml")
    out["taxonomy"] = _expect_list(
        taxonomy_doc.get("taxonomy"), "taxonomy", p / "taxonomy.yaml"
    )

    # Call sites: one file per site.
    sites_dir = p / "call_sites"
    if sites_dir.is_dir():
        for site_path in sorted(sites_dir.rglob("*.yaml")):
            site = _expect_mapping(_load_yaml(site_path), site_path)
            if site:
                out["call_sites"].append(site)

    # Failure modes: one file per call site + one `_chains.yaml` for chain scope.
    fms_dir = p / "failure_modes"
    if fms_dir.is_dir():
        for fm_path in sorted(fms_dir.rglob("*.yaml")):
            fm_doc = _expect_mapping(_load_yaml(fm_path), fm_path)
            shard = _expect_list(fm_doc.get("failure_modes"), "failure_modes", fm_path)
            out["failure_modes"].extend(shard)

    # Quality dimensions: one file per call site (judgment sites only).
    qd_dir = p / "quality_dimensions"
    if qd_dir.is_dir():
        for qd_path in sorted(qd_dir.rglob("*.yaml")):
            qd_doc = _expect_mapping(_load_yaml(qd_path), qd_path)
            shard = _expect_list(
                qd_doc.get("quality_dimensions"), "quality_dimensions", qd_path
            )
            out["quality_dimensions"].extend(shard)

    return out


# ---------------------------------------------------------------------------
# Writers — used by subagents and the finalize/dedup scripts. Each writer is
# self-contained and creates parent dirs as needed.
# ---------------------------------------------------------------------------

def _dump(path: Path, doc: Mapping[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    text = yaml.safe_dump(dict(doc), sort_keys=False, allow_unicode=True)
    path.write_text(text, encoding="utf-8")


def write_meta(evals_dir: Path, version: str, product_hint: str | None,
               runtime: Mapping[str, Any],
               progress: Mapping[str, Any] | None = None) -> Path:
    path = pipeline_dir(evals_dir) / "meta.yaml"
    doc: dict[str, Any] = {
        "version": version,
        "product_hint": product_hint,
        "runtime": dict(runtime),
    }
    if progress is not None:
        doc["progress"] = dict(progress)
    _dump(path, doc)
    return path


def write_packs(evals_dir: Path, packs: list[Mapping[str, Any]]) -> Path:
    path = pipeline_dir(evals_dir) / "packs.yaml"
    _dump(path, {"packs": list(packs)})
    return path


def write_product_profile(evals_dir: Path, profile: Mapping[str, Any]) -> Path:
    path = pipeline_dir(evals_dir) / "product_profile.yaml"
    _dump(path, {"product_profile": dict(profile)})
    return path


def write_invariants(evals_dir: Path,
                     implicit_invariants: list[Mapping[str, Any]],
                     invariant_coverage: list[Mapping[str, Any]]) -> Path:
    path = pipeline_dir(evals_dir) / "invariants.yaml"
    _dump(path, {
        "implicit_invariants": list(implicit_invariants),
        "invariant_coverage": list(invariant_coverage),
    })
    return path


def write_chains(evals_dir: Path, chains: list[Mapping[str, Any]]) -> Path:
    path = pipeline_dir(evals_dir) / "chains.yaml"
    _dump(path, {"chains": list(chains)})
    return path


def write_taxonomy(evals_dir: Path, taxonomy: list[Mapping[str, Any]]) -> Path:
    path = pipeline_dir(evals_dir) / "taxonomy.yaml"
    _dump(path, {"taxonomy": list(taxonomy)})
    return path


def write_call_site(evals_dir: Path, site: Mapping[str, Any]) -> Path:
    sid = site.get("id")
    if not isinstance(sid, str) or not sid:
        raise ValueError("call_site missing id")
    path = pipeline_dir(evals_dir) / "call_sites" / f"{id_path(sid)}.yaml"
    _dump(path, dict(site))
    return path


def write_failure_modes_for_site(evals_dir: Path, call_site_id: str,
                                 failure_modes: list[Mapping[str, Any]]) -> Path:
    path = pipeline_dir(evals_dir) / "failure_modes" / f"{id_path(call_site_id)}.yaml"
    _dump(path, {"failure_modes": list(failure_modes)})
    return path


def write_chain_failure_modes(evals_dir: Path,
                              failure_modes: list[Mapping[str, Any]]) -> Path:
    path = pipeline_dir(evals_dir) / "failure_modes" / "_chains.yaml"
    _dump(path, {"failure_modes": list(failure_modes)})
    return path


def write_quality_dimensions_for_site(evals_dir: Path, call_site_id: str,
                                      quality_dimensions: list[Mapping[str, Any]]) -> Path:
    path = pipeline_dir(evals_dir) / "quality_dimensions" / f"{id_path(call_site_id)}.yaml"
    _dump(path, {"quality_dimensions": list(quality_dimensions)})
    return path


# `_meta` keys the orchestrator must never destroy when re-stamping. `locked_fields`/`human_edited`
# drive re-run safety; `materialized_at`/`body_digest` are how a human edit of a platform-materialized
# body is detected. Rebuilding `_meta` without them silently disarms that detection.
_PRESERVED_META_KEYS = ("locked_fields", "human_edited", "materialized_at", "body_digest")

VALID_GROUNDING = ("observed", "none")


def stamp_meta(grader_path: Path, author: str, synth_inputs_digest: str,
               author_contract_version: int, grounding: str | None = None) -> Path:
    """Deterministically stamp the orchestrator-owned `_meta` provenance block onto
    a grader file *after* the author returns its body.

    Fills `author`, `synthesized_at` (ISO timestamp), `synth_inputs_digest`, and
    `author_contract_version`. PRESERVES the keys in `_PRESERVED_META_KEYS` on re-run and
    never clobbers them. `_meta` is orchestrator-owned per the contract Roles table — authors
    and per-site subagents do not write it.

    `grounding` records what the grader was written from: `observed` (the call site's real traces
    were fetched and read — the only value a synthesis can stamp, since the A.0 gate makes
    grounding unconditional). It is the audit trail that the judge prompt was calibrated against
    production, so it is never invented here — pass it explicitly, or omit it to carry forward
    whatever the file already had.
    """
    if grounding is not None and grounding not in VALID_GROUNDING:
        raise ValueError(f"stamp_meta: grounding must be one of {VALID_GROUNDING}, got {grounding!r}")
    grader_path = Path(grader_path)
    doc = yaml.safe_load(grader_path.read_text(encoding="utf-8"))
    if not isinstance(doc, dict):
        raise ValueError(f"stamp_meta: {grader_path} is not a YAML mapping")
    existing = doc.get("_meta")
    meta: dict[str, Any] = {
        "author": author,
        "synthesized_at": _now_iso(),
        "synth_inputs_digest": synth_inputs_digest,
        "author_contract_version": author_contract_version,
    }
    if grounding is not None:
        meta["grounding"] = grounding
    elif isinstance(existing, dict) and "grounding" in existing:
        meta["grounding"] = existing["grounding"]
    if isinstance(existing, dict):
        for key in _PRESERVED_META_KEYS:
            if key in existing:
                meta[key] = existing[key]
    doc["_meta"] = meta
    _dump(grader_path, doc)
    return grader_path


# ---------------------------------------------------------------------------
# Shard discovery (used by lock + validate).
# ---------------------------------------------------------------------------

def iter_shard_paths(evals_dir: Path) -> list[Path]:
    """All shard files under .tessary/pipeline/, in stable sorted order."""
    p = pipeline_dir(evals_dir)
    if not p.is_dir():
        return []
    return sorted([f for f in p.rglob("*.yaml") if f.is_file()])


# ---------------------------------------------------------------------------
# Lock file — tracks which paths each step has produced, with content SHAs.
# Used for deterministic resume: a step is considered complete only when every
# path it recorded is still present and its content hashes match.
# ---------------------------------------------------------------------------

LOCK_FILENAME = ".synth-lock.yaml"


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _now_iso() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _lock_path(evals_dir: Path) -> Path:
    return evals_dir / LOCK_FILENAME


def read_lock(evals_dir: Path) -> dict[str, Any]:
    """Load the lock file, returning an empty mapping if absent or malformed."""
    p = _lock_path(evals_dir)
    if not p.is_file():
        return {}
    try:
        doc = yaml.safe_load(p.read_text(encoding="utf-8"))
    except yaml.YAMLError:
        return {}
    return doc if isinstance(doc, dict) else {}


def write_lock(evals_dir: Path, lock: Mapping[str, Any]) -> Path:
    """Serialize the lock atomically (write-then-rename)."""
    p = _lock_path(evals_dir)
    p.parent.mkdir(parents=True, exist_ok=True)
    tmp = p.with_suffix(p.suffix + ".tmp")
    tmp.write_text(yaml.safe_dump(dict(lock), sort_keys=False, allow_unicode=True),
                   encoding="utf-8")
    tmp.replace(p)
    return p


def _rel(evals_dir: Path, path: Path) -> str:
    return path.resolve().relative_to(evals_dir.resolve()).as_posix()


def lock_paths(evals_dir: Path, step: str, paths: Iterable[Path]) -> Path:
    """Record `paths` under `step` and capture each file's current SHA.

    Extends rather than replaces: calling this multiple times for the same step
    merges new paths into the step's list. Paths already recorded get their
    SHA refreshed to the current content. Mirrors the per-file SHA under the
    legacy `shards`/`graders` buckets so the bundle validator keeps working.
    """
    lock = read_lock(evals_dir)
    lock.setdefault("version", 1)
    lock["synthesized_at"] = _now_iso()
    completed = lock.setdefault("completed_steps", {})
    shards = lock.setdefault("shards", {})
    graders = lock.setdefault("graders", {})

    step_record = completed.setdefault(step, {"at": _now_iso(), "outputs": []})
    if not isinstance(step_record, dict):
        step_record = {"at": _now_iso(), "outputs": []}
        completed[step] = step_record
    step_record["at"] = _now_iso()
    out_list = step_record.setdefault("outputs", [])

    for raw in paths:
        path = Path(raw)
        if not path.is_file():
            raise FileNotFoundError(f"lock_paths: not a file: {path}")
        rel = _rel(evals_dir, path)
        if rel not in out_list:
            out_list.append(rel)
        sha = _sha256(path)
        if rel.startswith("graders/"):
            graders[grader_lock_key(rel)] = sha
        else:
            shards[rel] = sha

    out_list.sort()
    write_lock(evals_dir, lock)
    return _lock_path(evals_dir)


def file_locked(evals_dir: Path, path: Path) -> bool:
    """True iff `path` exists and its current SHA matches the lock entry."""
    if not path.is_file():
        return False
    lock = read_lock(evals_dir)
    try:
        rel = _rel(evals_dir, path)
    except ValueError:
        return False
    recorded = (
        lock.get("graders", {}).get(grader_lock_key(rel)) if rel.startswith("graders/")
        else lock.get("shards", {}).get(rel)
    )
    if not isinstance(recorded, str):
        return False
    return recorded == _sha256(path)


def step_complete(evals_dir: Path, step: str) -> bool:
    """True iff every path recorded under `step` is present and matches its lock SHA."""
    lock = read_lock(evals_dir)
    completed = lock.get("completed_steps", {})
    record = completed.get(step)
    if not isinstance(record, dict):
        return False
    outputs = record.get("outputs")
    if not isinstance(outputs, list) or not outputs:
        return False
    for rel in outputs:
        if not isinstance(rel, str):
            return False
        if not file_locked(evals_dir, evals_dir / rel):
            return False
    return True


# ---------------------------------------------------------------------------
# CLI — small dispatcher so the orchestrator can drive lock/check from Bash.
# ---------------------------------------------------------------------------

def _cli(argv: list[str]) -> int:
    import argparse
    ap = argparse.ArgumentParser(prog="pipeline_io.py")
    sub = ap.add_subparsers(dest="cmd", required=True)

    p_lock = sub.add_parser("lock", help="Record paths under a step.")
    p_lock.add_argument("step")
    p_lock.add_argument("paths", nargs="+")
    p_lock.add_argument("--evals-dir", default=".tessary")

    p_check_step = sub.add_parser("check-step",
                                  help="Exit 0 if step is recorded and all paths match.")
    p_check_step.add_argument("step")
    p_check_step.add_argument("--evals-dir", default=".tessary")

    p_check_file = sub.add_parser("check-file",
                                  help="Exit 0 if file is recorded and content matches.")
    p_check_file.add_argument("path")
    p_check_file.add_argument("--evals-dir", default=".tessary")

    p_stamp = sub.add_parser("stamp-meta",
                             help="Stamp the orchestrator-owned _meta block onto a grader file.")
    p_stamp.add_argument("path")
    p_stamp.add_argument("--author", required=True)
    p_stamp.add_argument("--synth-inputs-digest", required=True)
    p_stamp.add_argument("--author-contract-version", type=int, default=8)
    p_stamp.add_argument("--grounding", choices=VALID_GROUNDING, default=None,
                         help="what this grader was written from: `observed` (the call site's real "
                              "traces were read). Omit to carry forward the value already on the file.")
    # Accepted and ignored (stamp-meta operates on an absolute/explicit path);
    # mirrors the sibling subcommands so the documented orchestrator invocation
    # works verbatim.
    p_stamp.add_argument("--evals-dir", default=".tessary")

    args = ap.parse_args(argv)

    if args.cmd == "stamp-meta":
        stamp_meta(Path(args.path), args.author, args.synth_inputs_digest,
                   args.author_contract_version, args.grounding)
        return 0

    evals = Path(args.evals_dir).resolve()

    if args.cmd == "lock":
        lock_paths(evals, args.step, [Path(p) for p in args.paths])
        return 0
    if args.cmd == "check-step":
        return 0 if step_complete(evals, args.step) else 1
    if args.cmd == "check-file":
        return 0 if file_locked(evals, Path(args.path)) else 1
    return 2


if __name__ == "__main__":
    sys.exit(_cli(sys.argv[1:]))
