#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Gate the vendored evals-plugin contract: test it, and prove it is current.
#
# The plugin lives in tessaryai/plugins, which is PUBLIC and deliberately runs no PR CI — a workflow
# there would put its configuration, logs and fixtures in the open. So the enforcement lives here, in
# the private repo, against the vendored copy under contract/. The code under test is public; the
# tests, fixtures and CI logs are not. Nothing this script does writes to, or leaves a trace in, the
# public repo: phase 2 only READS raw file contents over unauthenticated HTTPS.
#
# Two phases:
#   1. pytest over contract/tests — the validator rules the platform's import depends on.
#   2. freshness — diff each vendored file against the plugin's live `main`.
#
# Phase 2 needs network. Offline it WARNS and passes, so a local `task check` on a plane still works;
# in CI ($CI set) it is a hard failure, because that is the run whose whole job is catching drift.
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
contract_dir="$repo_root/contract"
raw_base="https://raw.githubusercontent.com/tessaryai/plugins/main/plugins/evals"

fail() { echo "vendored-plugin: $1" >&2; exit 1; }

# --- phase 1: the validator's rules -----------------------------------------------------------

# validate.py is PLATFORM-OWNED (the plugin dropped its validator with synthesis in v0.23.0);
# a sync will not restore it — its absence means someone deleted the platform's copy.
[[ -f "$contract_dir/validate.py" ]] \
  || fail "contract/validate.py is missing — it is platform-owned; restore it from git history"

python3 -c 'import yaml' 2>/dev/null \
  || fail "PyYAML is required to exercise the vendored validator (pip install pyyaml)"
python3 -c 'import pytest' 2>/dev/null \
  || fail "pytest is required (pip install pytest)"

echo "vendored-plugin: running validator tests…"
python3 -m pytest "$contract_dir/tests" -q --no-header \
  || fail "the vendored validator does not behave as this platform's import assumes"

# --- phase 2: is the vendored copy current? ---------------------------------------------------

# The vendored file list, shared with scripts/sync-evals-contract.sh so a newly-vendored file cannot
# be silently left out of this check.
# shellcheck source=lib/vendored-plugin-files.sh
source "$repo_root/scripts/lib/vendored-plugin-files.sh"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

# Fetch $1 to $2 and print the HTTP status, or "000" when the request never got an answer.
#
# The status is read directly rather than inferred from curl's exit taxonomy: with `-f` a 404 exits
# 22, but adding --retry changes that to 56, so branching on the exit code silently stops
# distinguishing "the plugin deleted this file" from "the CDN blipped". `--retry 3` (without
# --retry-all-errors) covers transient failures and timeouts while leaving a 404 to answer at once.
curl_status() {
  curl -sSL --max-time 20 --retry 3 -o "$2" -w '%{http_code}' "$1" 2>/dev/null || echo 000
}

if [[ "$(curl_status "$raw_base/output_format.md" "$tmp/probe")" != 200 ]]; then
  if [[ -n "${CI:-}" ]]; then
    fail "cannot reach the plugin repo to verify the vendored copy is current (this check is the point of the CI run)"
  fi
  echo "vendored-plugin: OFFLINE — skipping the freshness check (CI enforces it)" >&2
  echo "vendored-plugin: OK (validator tests only)"
  exit 0
fi

stale=()
for remote in "${VENDORED_PLUGIN_FILES[@]}"; do
  base="$(basename "$remote")"
  local_file="$contract_dir/$base"
  [[ -f "$local_file" ]] || { stale+=("$base (not vendored at all)"); continue; }
  status="$(curl_status "$raw_base/$remote" "$tmp/$base")"
  case "$status" in
    200) ;;
    # Gone upstream: a contract change the platform has to react to, not a blip to shrug off.
    404) stale+=("$base (missing upstream — deleted or moved in the plugin?)"); continue ;;
    # Anything else is transport or a server error. Reporting it as "deleted in the plugin" would
    # turn one CDN blip into eight false drift reports, so refuse to render a verdict instead.
    *) fail "HTTP $status fetching $remote — cannot prove the vendored copy is current" ;;
  esac
  cmp -s "$local_file" "$tmp/$base" || stale+=("$base")
done

if (( ${#stale[@]} > 0 )); then
  echo "vendored-plugin: the vendored contract is STALE against tessaryai/plugins@main:" >&2
  printf '  - %s\n' "${stale[@]}" >&2
  echo "" >&2
  echo "  Re-run: scripts/sync-evals-contract.sh   then review 'git diff -- contract/'." >&2
  echo "  A drifted contract is silent by construction: BundleAssembler ignores unknown YAML keys," >&2
  echo "  so a field the plugin added and this platform never learned looks exactly like a field" >&2
  echo "  nobody wrote." >&2
  exit 1
fi

echo "vendored-plugin: OK (validator tests pass; vendored copy matches plugins@main)"
