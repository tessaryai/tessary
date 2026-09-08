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
# Two distinct version axes (do NOT conflate):
#   * contract version       — the on-disk grader contract (schema $id, AUTHORING_CONTRACT).
#   * author contract version — what a conformant author declares; may legitimately
#                               lag the contract version when a bump is author-transparent
#                               (e.g. v9 is author-transparent, so the author declares 8).
# Invariant: schema $id version == AUTHORING_CONTRACT version, and
#            1 <= skill author contract_version <= contract version.
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
contract_dir="$repo_root/contract"
skill="$repo_root/claude-skill/evals-prompt/SKILL.md"

fail() { echo "contract-consistency: $1" >&2; exit 1; }

# --- contract version: schema $id (grader.vN.json) ---
schema_id_ver="$(grep -oE 'grader\.v[0-9]+\.json' "$contract_dir/grader.schema.json" | head -1 \
  | sed -E 's/grader\.v([0-9]+)\.json/\1/')"
[[ -n "$schema_id_ver" ]] || fail "could not parse contract version from grader.schema.json \$id"

# --- contract version: AUTHORING_CONTRACT header + body line ---
authoring_hdr_ver="$(grep -oE '^# Grader-author contract \(v[0-9]+\)' "$contract_dir/AUTHORING_CONTRACT.md" \
  | head -1 | sed -E 's/.*\(v([0-9]+)\).*/\1/')"
authoring_body_ver="$(grep -oE '\*\*Contract version\*\*: [0-9]+' "$contract_dir/AUTHORING_CONTRACT.md" \
  | head -1 | sed -E 's/.*: ([0-9]+).*/\1/')"
[[ -n "$authoring_hdr_ver" ]] || fail "could not parse 'Grader-author contract (vN)' header"
[[ -n "$authoring_body_ver" ]] || fail "could not parse '**Contract version**: N' line"

if [[ "$schema_id_ver" != "$authoring_hdr_ver" || "$schema_id_ver" != "$authoring_body_ver" ]]; then
  fail "contract version mismatch: grader.schema.json=v$schema_id_ver, AUTHORING header=v$authoring_hdr_ver, AUTHORING body=v$authoring_body_ver — these must agree"
fi
contract_ver="$schema_id_ver"

# --- author contract version: evals-prompt skill frontmatter ---
skill_ver="$(grep -oE '^contract_version: [0-9]+' "$skill" | head -1 | sed -E 's/.*: ([0-9]+).*/\1/')"
[[ -n "$skill_ver" ]] || fail "could not parse 'contract_version:' from $skill"
if (( skill_ver < 1 || skill_ver > contract_ver )); then
  fail "evals-prompt SKILL.md author contract_version=$skill_ver is out of range (must be 1..$contract_ver, the vendored contract version)"
fi

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

echo "contract-consistency: OK (contract v$contract_ver, evals-prompt author contract_version $skill_ver)"
