#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# The version-source check (D1/D9, epic 7 clause 1, #941/#1114 review).
#
# WHAT CHANGED, AND WHY THIS SCRIPT IS NOT WHAT IT USED TO BE. This file used to compare ~14
# hand-typed `0.1.0` literals against a root `VERSION` file and fail when any of them drifted. The
# failure it guarded was real: bump the version, miss one literal, and a self-hoster with the
# corresponding env var unset was silently pinned to a STALE image forever — `docker compose pull`
# still exits 0 against the old tag, so nothing went red. But the guard was compensating for the
# design, not fixing it. `VERSION` was not a build input; it was a value that had to be copied by
# hand into every file that could not read it, because compose's `${VAR:-X}` syntax and a plain JS
# string literal cannot read a file.
#
# THE DESIGN NOW: the git tag `v<semver>` that .github/workflows/release.yml pushes is the single
# source of truth for a published version, and NOTHING in this repository holds a copy of it.
#   - docker-compose.yml, docker-compose.dev.yml and sandbox-runner/launcher/server.js default to
#     the FLOATING `-latest` tag, which every release repoints (release.yml's finalize pushes
#     `<service>-latest` for all four services on Docker Hub). An unset env var can therefore
#     never resolve to a stale pin, because it never resolves to a pin at all.
#   - The PUBLISHED compose artifact is still fully pinned: scripts/publish-compose-artifact.sh
#     runs scripts/lib/pin-compose-version.py over the file on the way out, stamping the release's
#     own version into each default. That is epic 7 clause 1's pinning requirement, discharged by a
#     generated substitution rather than a hand-typed fallback — and it is checked, by clause (4)
#     of scripts/check-compose-artifact.sh.
#   - .env.example and the self-hosting pages name `<version>` placeholders, so a reader copies a
#     shape and fills in a release rather than copying a number that was true once.
#
# WHAT THIS SCRIPT ASSERTS, which is the STRONGER property that makes the old failure impossible
# rather than merely detectable:
#   (a) NO pinned Tessary image or compose-artifact version literal exists anywhere in the tracked
#       tree. There is no literal to forget to bump, and none can come back unnoticed.
#   (b) Every default site that a machine resolves — six of them — is present AND floating. (a)
#       alone would pass on a file that deleted its default outright; this is the half that says
#       the defaults still exist and still say `-latest`.
# Pure text, no Docker, no network, no git tag lookup — cheap enough to run on every PR.
# scripts/check-selfhost-images.sh is the separate, Docker-and-network leg that asks a REGISTRY
# whether the tags actually resolve; it reads its expected version from the newest git tag.
#
# ONE CLASS OF FILE IS EXEMPT, and only from the environment: an append-only decision ledger,
# whose rows state what was decided and what was true when — a row rewritten to match today's tree
# stops being a record. It is named by scripts/check.sh rather than here (boundary rule 5); see
# VERSION_LITERAL_EXEMPT below.
#
# MUST GO RED ON A REINTRODUCED LITERAL — proven, not asserted: run with
#   VERSION_SOURCE_SELFTEST=1 bash scripts/check-version-consistency.sh
# which writes a pinned literal and a de-floated default into a scratch copy of the tree and
# asserts this script reports both.

set -euo pipefail
P=check-version-consistency
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if ! command -v python3 >/dev/null 2>&1; then
    echo "$P: needs python3 on PATH" >&2
    exit 1
fi

# --- the self-test arm: build a scratch copy, break it two ways, and require a red -----------
if [ "${VERSION_SOURCE_SELFTEST:-0}" = "1" ]; then
    T="$(mktemp -d)"
    trap 'rm -rf "$T"' EXIT
    mkdir -p "$T/scripts/lib" "$T/docs/self-hosting" "$T/sandbox-runner/launcher"
    for f in docker-compose.yml docker-compose.dev.yml .env.example \
             docs/self-hosting/setup.mdx docs/self-hosting/upgrading.mdx \
             sandbox-runner/launcher/server.js scripts/check-version-consistency.sh; do
        cp "$ROOT/$f" "$T/$f"
    done
    # Breakage 1: a pinned literal comes back in a documented pull instruction.
    printf '\n# AGENT_IMAGE=tessaryai/tessary:agent-sandbox-0.1.0\n' >> "$T/.env.example"
    # Breakage 2: a floating default is replaced by a pin.
    python3 - "$T/docker-compose.yml" <<'PY'
import sys
p = sys.argv[1]
t = open(p, encoding="utf-8").read()
open(p, "w", encoding="utf-8").write(t.replace("backend-${TESSARY_VERSION:-latest}", "backend-${TESSARY_VERSION:-0.1.0}", 1))
PY
    echo "VERSION_SOURCE_SELFTEST=1: checking a scratch tree with a reintroduced literal and a de-floated default." >&2
    set +e
    (cd "$T" && VERSION_SOURCE_SELFTEST=0 bash scripts/check-version-consistency.sh)
    status=$?
    set -e
    if [ "$status" -eq 0 ]; then
        echo "SELFTEST FAILED: a reintroduced version literal was expected to fail this script, and it did not." >&2
        exit 1
    fi
    echo "SELFTEST OK: the reintroduced literal and the de-floated default both failed this script (see FAIL lines above)." >&2
    exit 0
fi

cd "$ROOT"
set +e
python3 - <<'PY'
import os
import re
import subprocess
import sys

fail = False

# --- (a) no pinned Tessary version literal anywhere -------------------------------------------
# `tessaryai/tessary:backend-0.1.0`, `:compose-0.1.0`, `:agent-sandbox-0.1.0`, and the nested
# compose form `${TESSARY_VERSION:-0.1.0}` however it is spelled.
LITERAL = re.compile(r"tessaryai/tessary:[A-Za-z][A-Za-z-]*-[0-9]+\.[0-9]+\.[0-9]+")
NESTED = re.compile(r"TESSARY_VERSION:-[0-9]+\.[0-9]+\.[0-9]+")

# Named, reasoned exemptions: a file whose version literals RECORD something that was true at a
# date rather than instructing a machine or a reader to pull it.
EXEMPT = {
    # This script's own header and self-test, which must be able to name the literal they forbid.
    "scripts/check-version-consistency.sh",
}
# Further exemptions come from the environment rather than from this list, because the only ones
# that exist are OVERLAY paths and boundary rule 5 forbids this script from naming them — it ships
# in the public export, where that directory has been deleted. scripts/check.sh's paid column
# passes them in (`RUN_ENV:`), the same mechanism and for the same reason as
# check-classifier-quality-doc.sh's inputs. Space-separated, repo-relative.
EXEMPT |= {p for p in os.environ.get("VERSION_LITERAL_EXEMPT", "").split() if p}

try:
    tracked = subprocess.run(
        ["git", "ls-files", "-z"], capture_output=True, check=True
    ).stdout.decode("utf-8", "replace").split("\0")
except (OSError, subprocess.CalledProcessError):
    # A clean-room export copy is not a git checkout (the quickstart rehearsal runs there). Walk
    # the tree instead; the exclusions mirror what `git ls-files` would never list anyway.
    tracked = []
    SKIP_DIRS = {".git", "node_modules", "target", "dist", ".venv", "__pycache__"}
    for dirpath, dirnames, filenames in os.walk("."):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for name in filenames:
            tracked.append(os.path.relpath(os.path.join(dirpath, name), "."))

scanned = 0
for path in tracked:
    if not path or path in EXEMPT or not os.path.isfile(path):
        continue
    try:
        with open(path, encoding="utf-8") as fh:
            text = fh.read()
    except (OSError, UnicodeDecodeError):
        continue  # binary or unreadable: carries no version literal to find
    scanned += 1
    for found in set(LITERAL.findall(text)) | set(NESTED.findall(text)):
        print(
            f"FAIL: {path} hard-codes a published version: \"{found}\". No file in this tree may "
            "hold a copy of the version — the git tag release.yml pushes is the only source of "
            "truth, and a second copy is the one that goes stale. Use the floating `-latest` tag "
            "for a machine-resolved default, or a `<version>` placeholder in prose.",
            file=sys.stderr,
        )
        fail = True

# --- (b) every machine-resolved default is present and floating -------------------------------
# (file, human label, exact string the file must contain, how many times)
SITES = [
    ("docker-compose.yml", "backend image default",
     "image: ${BACKEND_IMAGE:-tessaryai/tessary:backend-${TESSARY_VERSION:-latest}}", 1),
    ("docker-compose.yml", "frontend image default",
     "image: ${FRONTEND_IMAGE:-tessaryai/tessary:frontend-${TESSARY_VERSION:-latest}}", 1),
    ("docker-compose.yml", "sandbox-runner image default",
     "image: ${SANDBOX_RUNNER_IMAGE:-tessaryai/tessary:sandbox-runner-${TESSARY_VERSION:-latest}}", 1),
    ("docker-compose.yml", "AGENT_IMAGE default",
     "AGENT_IMAGE: ${AGENT_IMAGE:-tessaryai/tessary:agent-sandbox-${TESSARY_VERSION:-latest}}", 1),
    ("docker-compose.dev.yml", "AGENT_IMAGE default",
     "AGENT_IMAGE: ${AGENT_IMAGE:-tessaryai/tessary:agent-sandbox-${TESSARY_VERSION:-latest}}", 1),
    ("sandbox-runner/launcher/server.js", "AGENT_IMAGE code default",
     "process.env.AGENT_IMAGE || 'tessaryai/tessary:agent-sandbox-latest'", 1),
]

for path, label, needle, count in SITES:
    try:
        with open(path, encoding="utf-8") as fh:
            text = fh.read()
    except OSError as exc:
        print(f"FAIL: {path} ({label}) — could not read file: {exc}", file=sys.stderr)
        fail = True
        continue
    found = text.count(needle)
    if found != count:
        print(
            f"FAIL: {path} ({label}) — expected {count} occurrence(s) of the floating default\n"
            f"         {needle}\n"
            f"       found {found}. A default that is missing, or that names a version instead of "
            "`latest`, is how the stale-pin failure gets back in: an unset env var must resolve to "
            "a tag the release repoints, never to a fixed one.",
            file=sys.stderr,
        )
        fail = True

if fail:
    sys.exit(1)
print(f"check-version-consistency: no file holds a version literal ({scanned} scanned), and all "
      f"{len(SITES)} machine-resolved image defaults float to the release-repointed tag.")
PY
status=$?
set -e
exit "$status"
