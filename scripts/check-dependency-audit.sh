#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Dependency-vulnerability audit gate. Runs, per ecosystem, a scanner that needs no GitHub-side
# feature toggle, since this repo's own Dependabot-alerts API is disabled. Invoke it directly:
#   bash scripts/check-dependency-audit.sh
#
# Not wired into `task check` / scripts/check.sh's manifest (same as check-conformance-parity.sh
# and check-migrations-populated.sh): this is not a per-PR gate, and it is not in CI either, run
# it on demand with your own NVD key.
#
# Suppressions live in one file for all three ecosystems: `.github/dependency-suppressions.yml`,
# a flat list of {id, ecosystems, reason, expires}. This script reads it once per ecosystem and
# translates it into that ecosystem's own native suppression mechanism (pnpm audit's repeatable
# `--ignore`, pip-audit's `--ignore-vuln`, and a dependency-check suppression XML generated on the
# fly) rather than hand-authoring three files that could drift from each other. An expired
# suppression is dropped silently and therefore re-fires as a real finding: that is the point.
#
# SBOM generation is not covered yet: there is no publish step to attach one to.
#
# Not in CI: no test or check may require a real credential, and the Maven leg hard-fails without
# an NVD_API_KEY. Automated dependency coverage is Dependabot alerts plus CodeQL (both keyless).
# A human with a free NVD key (https://nvd.nist.gov/developers/request-an-api-key) exports
# NVD_API_KEY and runs this locally; the pnpm-audit and pip-audit legs need no key. If
# NVD_API_KEY is set it is passed through as -Dnvd.api.key; if unset, the Maven leg fails loudly.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

SUPPRESSIONS_FILE=".github/dependency-suppressions.yml"
fail=0

if ! command -v uv >/dev/null 2>&1; then
  echo "check-dependency-audit: uv is not installed — see https://docs.astral.sh/uv/getting-started/installation/" >&2
  exit 1
fi
if ! command -v pnpm >/dev/null 2>&1; then
  echo "check-dependency-audit: pnpm is not installed — see https://pnpm.io/installation" >&2
  exit 1
fi

# ---- suppressions, filtered by ecosystem and expiry, one id per line ----
# `uv run --with pyyaml` gets a PyYAML-capable interpreter without assuming the system python3
# has it, and without adding a project dependency anywhere for a script this small: the same
# ephemeral-tool pattern `uv run --with pip-audit` uses below.
_read_suppressions() {
  uv run --with pyyaml python3 - "$SUPPRESSIONS_FILE" "$1" <<'PY'
import datetime
import sys

import yaml

path, ecosystem = sys.argv[1], sys.argv[2]
with open(path) as f:
    data = yaml.safe_load(f) or {}

today = datetime.date.today()
for entry in data.get("suppressions") or []:
    expires = entry.get("expires")
    if expires and datetime.date.fromisoformat(str(expires)) < today:
        continue  # expired: re-fires as a real finding rather than staying silently suppressed
    if ecosystem in (entry.get("ecosystems") or []):
        print(entry["id"])
PY
}

# ---- 1. maven: backend, open profile only (matches backend:check:open's own -P '!paid') ----
# org.owasp:dependency-check-maven 13.0.0. Invoked by full coordinate rather than declared in
# backend/pom.xml's <pluginManagement> because it is never bound to a lifecycle phase: nothing
# here wants it running on every `mvn verify` alongside Spotless/PMD/SpotBugs, only on this
# script's own explicit call.
echo "== maven: backend (open profile) =="
_maven_suppression_xml="$(mktemp -t dependency-check-suppressions-XXXXXX.xml)"
# rc is assigned inside the trap; declared here so shellcheck (SC2154) and readers see it.
rc=0
trap 'rc=$?; rm -f "$_maven_suppression_xml"; exit $rc' EXIT
{
  echo '<?xml version="1.0" encoding="UTF-8"?>'
  echo '<suppressions xmlns="https://jeremylong.github.io/DependencyCheck/dependency-suppression.1.4.xsd">'
  while IFS= read -r _id; do
    [ -z "$_id" ] && continue
    echo "  <suppress><notes>from $SUPPRESSIONS_FILE</notes><cve>$_id</cve></suppress>"
  done <<<"$(_read_suppressions maven)"
  echo '</suppressions>'
} >"$_maven_suppression_xml"

# Empty-array expansion, used three times in this file: on bash < 4.4 (macOS's stock /bin/bash is
# 3.2), "${arr[@]}" on an empty array is an unbound-variable error under `set -u`, which used to
# abort the script before mvn ever ran and get silently laundered into a false pass by the EXIT
# trap's own successful `rm -f`. `${arr[@]+"${arr[@]}"}` expands to nothing when the array is
# empty and to the quoted elements otherwise, on every bash from 3.2 up; use it for any array that
# can legitimately be empty. The trap now also re-raises `$?` so a real command failure can't be
# laundered by the cleanup, though it doesn't rescue the expansion-abort case itself on 3.2.
_nvd_key_arg=()
if [ -n "${NVD_API_KEY:-}" ]; then
  _nvd_key_arg+=("-Dnvd.api.key=$NVD_API_KEY")
fi
if ! mvn -B -f backend/pom.xml -P '!paid' \
      org.owasp:dependency-check-maven:13.0.0:check \
      -DfailBuildOnCVSS=7 \
      -DsuppressionFiles="$_maven_suppression_xml" \
      ${_nvd_key_arg[@]+"${_nvd_key_arg[@]}"}; then
  echo "ERROR: OWASP dependency-check found an unsuppressed CVSS>=7 finding in the open Maven" >&2
  echo "       reactor. Add a documented suppression to $SUPPRESSIONS_FILE (ecosystems: [maven])" >&2
  echo "       if it is a false positive or accepted risk, or fix the dependency otherwise." >&2
  fail=1
fi

# ---- 2. the four pnpm workspaces in this checkout ----
_npm_ignore_args=()
while IFS= read -r _id; do
  [ -z "$_id" ] && continue
  _npm_ignore_args+=(--ignore "$_id")
done <<<"$(_read_suppressions npm)"

for _dir in frontend classify-service sandbox-runner/launcher sandbox-runner/agent-sandbox; do
  echo "== pnpm audit: $_dir =="
  if ! (cd "$_dir" && pnpm audit --audit-level=high ${_npm_ignore_args[@]+"${_npm_ignore_args[@]}"}); then
    echo "ERROR: pnpm audit found an unsuppressed high/critical advisory in $_dir. Add a" >&2
    echo "       documented suppression to $SUPPRESSIONS_FILE (ecosystems: [npm]) or bump the" >&2
    echo "       dependency." >&2
    fail=1
  fi
done

# ---- 3. the two uv workspaces in this checkout ----
_uv_ignore_args=()
while IFS= read -r _id; do
  [ -z "$_id" ] && continue
  _uv_ignore_args+=(--ignore-vuln "$_id")
done <<<"$(_read_suppressions uv)"

# `[ -d ] || continue` rather than a shortened list: a checkout without slack-service/ skips
# auditing it rather than dying on the cd.
for _dir in slack-service classifiers; do
  [ -d "$_dir" ] || { echo "== pip-audit: $_dir == skipped, not in this checkout"; continue; }
  echo "== pip-audit: $_dir =="
  # --all-extras: classifiers/pyproject.toml's optional extras (torch, transformers, ...) never
  # sync into a plain `uv run`'s env, so without this flag those CVE-prone packages go unscanned
  # while the audit silently reports only the base deps as covered.
  if ! (cd "$_dir" && uv run --frozen --all-extras --with pip-audit pip-audit ${_uv_ignore_args[@]+"${_uv_ignore_args[@]}"}); then
    echo "ERROR: pip-audit found an unsuppressed vulnerability in $_dir. Add a documented" >&2
    echo "       suppression to $SUPPRESSIONS_FILE (ecosystems: [uv]) or bump the dependency." >&2
    fail=1
  fi
done

if [ "$fail" -ne 0 ]; then
  exit 1
fi
echo "dependency audit: ok"
