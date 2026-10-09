#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# The vendored evals-plugin validator's rules: pytest over contract/tests. Offline and sub-second.
#
# This is phase 1 of scripts/check-vendored-plugin.sh, split out so it can run per PR. That script's
# phase 2 fetches tessaryai/plugins over the network, which is why the whole gate was EXCLUDED from
# scripts/check.sh; these rules need no network and were running nowhere per PR as a result. The
# freshness half still runs only from check-vendored-plugin.sh, which calls this script first, so
# the rules have one definition.
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
contract_dir="$repo_root/contract"

fail() { echo "vendored-plugin-rules: $1" >&2; exit 1; }

# validate.py is PLATFORM-OWNED (the plugin dropped its validator with synthesis in v0.23.0);
# a sync will not restore it — its absence means someone deleted the platform's copy.
[[ -f "$contract_dir/validate.py" ]] \
  || fail "contract/validate.py is missing — it is platform-owned; restore it from git history"

python3 -c 'import yaml' 2>/dev/null \
  || fail "PyYAML is required to exercise the vendored validator (pip install pyyaml)"
python3 -c 'import pytest' 2>/dev/null \
  || fail "pytest is required (pip install pytest)"

echo "vendored-plugin-rules: running validator tests…"
python3 -m pytest "$contract_dir/tests" -q --no-header -p no:cacheprovider \
  || fail "the vendored validator does not behave as this platform's import assumes"
echo "vendored-plugin-rules: OK"
