#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Assert the vendored evals contract is internally consistent.
#
# The platform repeatedly drifted because the contract version is restated in
# several files that are edited independently (the schema $id, the
# AUTHORING_CONTRACT header + "Contract version" line, the vendored VERSION pin,
# and the evals-prompt skill's author contract_version). This gate catches a
# mismatch at `task check` time instead of at grader-author time.
#
# WHAT THIS ASSERTS TODAY (narrowed 2026-09-09, see the block further down): the schema $id parses
# to a version, the platform-owned validator files are present, and contract/VERSION names a real
# release rather than 'unknown'. The version-AGREEMENT half is gone with the two markdown legs
# that supplied two of its three numbers.

set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
contract_dir="$repo_root/contract"

fail() { echo "contract-consistency: $1" >&2; exit 1; }

# --- contract version: schema $id (grader.vN.json) ---
schema_id_ver="$(grep -oE 'grader\.v[0-9]+\.json' "$contract_dir/grader.schema.json" | head -1 \
  | sed -E 's/grader\.v([0-9]+)\.json/\1/')"
[[ -n "$schema_id_ver" ]] || fail "could not parse contract version from grader.schema.json \$id"

# Removed 2026-09-09 under the standing rule in scripts/check.sh's header: no gate reads a .md
# or .mdx file. The two legs here compared the contract version as
# restated in contract/AUTHORING_CONTRACT.md (its `# Grader-author contract (vN)` header and its
# `**Contract version**: N` line) and the author version in claude-skill/evals-prompt/SKILL.md's
# frontmatter against the schema's own $id.
#
# That is a real drift class -- it is the one this gate was written for -- and losing it narrows
# this gate a lot. But both sources are prose files, so the gate red on document edits, which is
# the failure the rule exists to stop. If that agreement matters enough to gate, the version needs
# to live somewhere a machine owns; contract/VERSION already exists and is read below.

# --- the platform-owned validator must be present ---
# validate.py + pipeline_io.py stopped being vendored when the plugin dropped synthesis
# (v0.23.0): they are platform-owned now, but the contract tests import them and the E2B
# analyzer template bakes them, so their absence is still a broken contract dir.
[[ -f "$contract_dir/validate.py" && -f "$contract_dir/pipeline_io.py" ]] \
  || fail "contract/validate.py or pipeline_io.py is missing — these are platform-owned (not restored by a sync)"

# --- VERSION pin must record a real release, not 'unknown' ---
if grep -qE '^plugin_release: unknown' "$contract_dir/VERSION"; then
  fail "contract/VERSION plugin_release is 'unknown' — re-run scripts/sync-evals-contract.sh against the plugin checkout"
fi

echo "contract-consistency: OK (schema \$id parses as v$schema_id_ver, validator present, VERSION pin names a release)"
