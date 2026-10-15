#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Assert the vendored evals contract is internally consistent.
#
# WHAT THIS ASSERTS TODAY (narrowed 2026-09-09, see the block further down): the schema $id parses
# to a version and contract/VERSION names a real release rather than 'unknown'. The
# version-AGREEMENT half is gone with the two markdown legs that supplied two of its three numbers.

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
# `**Contract version**: N` line) against the schema's own $id.
#
# That is a real drift class -- it is the one this gate was written for -- and losing it narrows
# this gate a lot. But both sources are prose files, so the gate red on document edits, which is
# the failure the rule exists to stop. If that agreement matters enough to gate, the version needs
# to live somewhere a machine owns; contract/VERSION already exists and is read below.

# --- VERSION pin must record a real release, not 'unknown' ---
if grep -qE '^plugin_release: unknown' "$contract_dir/VERSION"; then
  fail "contract/VERSION plugin_release is 'unknown' — re-run scripts/sync-evals-contract.sh against the plugin checkout"
fi

echo "contract-consistency: OK (schema \$id parses as v$schema_id_ver, VERSION pin names a release)"
