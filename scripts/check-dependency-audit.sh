#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Dependency-vulnerability audit gate (#927 step 3, ledger item 29). Runs, per ecosystem, a
# scanner that needs no GitHub-side feature toggle — this repo's own Dependabot-alerts API is
# disabled (`gh api repos/tessaryai/tessary/dependabot/alerts` -> 403, measured
# 2026-09-01; flipping it needs a repo-admin human, see item 29 of the open-core execution
# plan — overlay-owned since #1293, not part of this repo's public export), so this script
# is the only thing that can go green today.
#
# NOT wired into `task check` / scripts/check.sh's manifest — that manifest's completeness
# assertion still names this row as EXCLUDED, same as check-conformance-parity.sh and
# check-migrations-populated.sh, for the same reason: this is not a per-PR gate, it is what
# Not wired into CI (the former .github/workflows/dependency-audit.yml was dropped on 2026-09-02 — see
# the NOT IN CI paragraph below); run on demand by a human with their own NVD key.
# Invoke it directly:
#   bash scripts/check-dependency-audit.sh
#
# SUPPRESSIONS, ONE FILE FOR ALL THREE ECOSYSTEMS. `.github/dependency-suppressions.yml` is the
# single source of truth — a flat list of {id, ecosystems, reason, expires}. This script reads it
# once per ecosystem and translates it into whichever native suppression mechanism that ecosystem's
# own tool already has (pnpm audit's repeatable `--ignore <GHSA>`, pip-audit's repeatable
# `--ignore-vuln <id>`, and a dependency-check suppression XML generated on the fly from the
# maven-tagged entries) rather than inventing a fourth format or hand-authoring three suppression
# files that could drift from each other. An EXPIRED suppression (`expires` in the past) is
# dropped silently here and therefore re-fires as a real finding — that is the point: a suppression
# with no expiry review date is exactly the "broken window" #927's AC asks this file to avoid.
#
# WHAT THIS DOES NOT COVER, ON PURPOSE.
#   - The one uv workspace inside the paid overlay directory (D2). It is not in the `uv` loop
#     below — simply absent, the same way .github/dependabot.yml simply omits it (see that file's
#     header and check-open-boundary.sh rule 8). Paid-tree dependency auditing is epic 5's
#     concern, not this open-core gate's. This script is itself subject to check-open-boundary.sh
#     rule 5 (an open script must not name the overlay directory by path), which is exactly why
#     this bullet describes it rather than spelling it out.
#   - SBOM generation: deferred to epic 7, which is the first thing in this tree to publish the
#     open image. There is no publish step yet to attach an SBOM to.
#
# NOT IN CI. Dropped from CI on 2026-09-02 by the owner's decision: no test or check may require a
# real credential, and the Maven leg here hard-fails without an NVD_API_KEY (measured 2026-09-01:
# `NvdApiException: Invalid API Key, length of 0` — a hard failure, not a throttled pass — so
# "run it keyless" was never an option). The former .github/workflows/dependency-audit.yml is
# deleted; automated dependency coverage is Dependabot alerts plus CodeQL (both keyless, both from
# #927). This script stays as an ON-DEMAND tool: a human with their own free NVD key
# (https://nvd.nist.gov/developers/request-an-api-key) exports NVD_API_KEY and runs it locally;
# the pnpm-audit and pip-audit legs need no key at all. If NVD_API_KEY is set it is passed through
# as -Dnvd.api.key; if unset the Maven leg fails loudly, which is the honest signal.
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
# has it (it doesn't, on this machine) and without adding a project dependency anywhere for a
# script this small — the same ephemeral-tool pattern `uv run --with pip-audit` uses below.
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
# org.owasp:dependency-check-maven 13.0.0 — verified latest stable on Maven Central 2026-09-01
# (maven-metadata.xml lastUpdated 20260803, no newer release since). Invoked by full coordinate
# rather than declared in backend/pom.xml's <pluginManagement> because it is never bound to a
# lifecycle phase — nothing here wants dependency-check running on every `mvn verify` alongside
# Spotless/PMD/SpotBugs, only on this script's own explicit call.
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

# EMPTY-ARRAY EXPANSION, THREE TIMES IN THIS FILE (#1016): on bash < 4.4 -- macOS's stock
# /bin/bash is 3.2 -- "${arr[@]}" on an EMPTY array is an unbound-variable error under `set -u`,
# and that error ABORTS the script at the first such expansion (here: before mvn ever ran, so the
# pnpm and pip legs below were never reached either). What made it a silent false PASS rather than a
# loud crash was the EXIT trap above: on bash 3.2 a trap whose last command succeeds (`rm -f`)
# replaces the script's exit status with its own 0. Measured, not inferred (crew review, PR #1022):
# no trap -> exit 1; `rm -f` trap -> exit 0; bash 4.3+ stops laundering the status. Two fixes:
# `${arr[@]+"${arr[@]}"}` expands to nothing when the array is empty and to the quoted elements
# otherwise on every bash from 3.2 up (use it for any array that can legitimately be empty) -- this
# is the load-bearing fix. The trap now re-raises `$?` so a genuine command failure can never be
# laundered into a pass by the cleanup. It does NOT rescue the expansion-abort case on 3.2: there,
# `$?` inside the trap is still 0 (the abort is not a command failure), measured. The idiom is what
# closes that case; the trap is defense-in-depth for everything the shell can actually report.
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

# ---- 2. the four open/glue pnpm workspaces (sandbox-runner/lambda and sandbox-runner/template
# both went with Track A -- the Lambda executor served /grade and /lint, and the grader sandbox
# template served the same two routes; see
#      item 30 of the open-core execution plan, overlay-owned since #1293) ----
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

# ---- 3. the two open/glue uv workspaces. The paid overlay's uv workspace is deliberately absent
#      from this loop — see the header. ----
_uv_ignore_args=()
while IFS= read -r _id; do
  [ -z "$_id" ] && continue
  _uv_ignore_args+=(--ignore-vuln "$_id")
done <<<"$(_read_suppressions uv)"

# `[ -d ] || continue` rather than a shortened list: #1293 moved slack-service/ into the overlay,
# and this loop is the one place both an open and a paid checkout read the same names. Skipping a
# directory that is not here keeps a paid checkout auditing it without the open one dying on a cd.
for _dir in slack-service classifiers; do
  [ -d "$_dir" ] || { echo "== pip-audit: $_dir == skipped, not in this checkout (overlay-owned since #1293)"; continue; }
  echo "== pip-audit: $_dir =="
  # --all-extras: classifiers/pyproject.toml's train/quality/annotation/otlp extras (torch,
  # transformers, accelerate, opentelemetry exporters, ...) are optional-dependencies, which a
  # plain `uv run` never syncs into the env pip-audit inspects — without this flag those
  # CVE-prone packages go unscanned while the audit silently reports only the base deps as
  # covered. slack-service has no extras today, so this is a no-op there (and in an open
  # checkout it is skipped above, not reached).
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
