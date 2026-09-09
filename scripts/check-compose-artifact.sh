#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# docker-compose.yml stays publishable as an OCI artifact, and stays runnable by someone who has
# cloned nothing (the one-command install).
#
# THE COMMAND THIS DEFENDS, published verbatim on tessary.ai's hero, its closing block and
# /llms.txt:
#
#   docker compose -f oci://docker.io/tessaryai/tessary:compose up -d -y
#
# What makes that fragile is that NOTHING about editing docker-compose.yml tells you it is also a
# published artifact. Three classes of ordinary, correct-looking edit break the remote install
# while leaving `docker compose up -d` in a clone perfectly green, so no other check in this repo
# would notice:
#
#   (1) A HOST-PATH BIND in a service the default profile starts. In a clone `./x` is the repo and
#       `${PWD}/x` is the repo root. In a remote install `./x` is Compose's own cache directory
#       (~/.cache/docker-compose/<hash>) and `${PWD}/x` is whatever directory the operator typed
#       the command in — so the first silently reads a file that is not there and the second
#       silently writes state outside Docker, which is the one thing the install promises not to
#       do. Named volumes are the only mount shape that means the same thing in both.
#   (2) SHORT-FORM `ports:` or an interpolated memory limit. `docker compose publish` loads the
#       file WITHOUT interpolation — that is exactly what preserves `${VAR}` for the consumer —
#       and its decoder is stricter than the one `docker compose config` uses: it reads `ports:`
#       entries as maps and every memory field as a number. `"${HTTP_PORT:-80}:8000"` and
#       `mem_limit: ${BACKEND_MEM_LIMIT:-2g}` each made the whole file refuse to publish. Neither
#       is visible to `docker compose config`, with or without `--no-interpolate`: that path
#       normalises both shapes and reports nothing.
#   (3) A `build:` block reaching the artifact. `docker compose publish` runs `docker push` for
#       every service that has one, so publishing the file as-is from a runner logged in to Docker
#       Hub pushes local images over the release's own multi-arch manifests. The artifact is built
#       from a build-stripped copy (scripts/lib/strip-compose-build.py); this script asserts that
#       the strip removes the build sections AND NOTHING ELSE.
#   (4) A FLOATING image default reaching the artifact. The repository holds no version literal —
#       the git tag release.yml pushes is the only source of truth — so this file's own defaults
#       are `${TESSARY_VERSION:-latest}` and the publisher stamps the release's version in
#       (scripts/lib/pin-compose-version.py). A remote install has no `.env` and no clone, so an
#       unstamped service would float there permanently; this script asserts the stamp pins every
#       one of the four AND CHANGES NOTHING ELSE.
#
# Pure text plus `docker compose config` (a client-side render — no daemon, no network), so it is
# a RUN|RUN row in scripts/check.sh. The end-to-end proof that the artifact actually publishes,
# resolves over oci:// and boots is a separate, Docker-heavy leg:
# scripts/check-selfhost-compose-artifact.sh, run by boot-checks.yml.
#
#   bash scripts/check-compose-artifact.sh              the tree
#   bash scripts/check-compose-artifact.sh --negative   in scratch copies: re-add a ${PWD} bind
#                                                       (red), a short-form port (red), an
#                                                       interpolated mem_limit (red)
#   --root=<dir>                                        check that tree instead (the negative arm's own use)
set -euo pipefail
P=check-compose-artifact
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NEGATIVE=0
for arg in "$@"; do
    case "$arg" in
        --negative) NEGATIVE=1 ;;
        --root=*) ROOT="${arg#--root=}" ;;
        *) echo "$P: unknown argument '$arg' (accepts --negative, --root=<dir>)" >&2; exit 2 ;;
    esac
done
cd "$ROOT"
COMPOSE=docker-compose.yml
# EMPTY, and that is the point. This used to name `alloy`, the one service allowed a host-path
# bind because it was profile-gated off and documented as clone-only (it mounted
# ./observability/alloy/config.alloy, which no remote install can resolve). That service is no
# longer defined in this compose file, so the open file has no clone-only service left and the
# exemption is empty rather than populated. Keep the mechanism: it is one variable, and the next
# service that genuinely cannot resolve remotely should be listed here and argued for, not
# quietly bound. An entry here is a claim that a remote install is unaffected — check the
# `profiles:` before adding one.
CLONE_ONLY_SERVICES=""
fail=0

command -v python3 >/dev/null 2>&1 || { echo "$P: needs python3 on PATH" >&2; exit 1; }
command -v docker >/dev/null 2>&1 || { echo "$P: needs the docker CLI on PATH (config renders client-side; no daemon needed)" >&2; exit 1; }

echo "$P: --- (1) no host-path bind in any service the default profile starts"
python3 - "$COMPOSE" "$CLONE_ONLY_SERVICES" <<'PY' || fail=1
import json, subprocess, sys

compose, clone_only = sys.argv[1], set(sys.argv[2].split())
# --profiles renders every service, including profile-gated ones, so the clone-only exemption is
# something this script states rather than something the render hides.
raw = subprocess.run(
    ["docker", "compose", "-f", compose, "--profile", "*", "config", "--format", "json"],
    capture_output=True, text=True,
)
if raw.returncode != 0:
    print(f"FAIL: `docker compose config` could not render {compose}:\n{raw.stderr}", file=sys.stderr)
    sys.exit(1)
model = json.loads(raw.stdout)
bad = 0
for name, svc in sorted(model.get("services", {}).items()):
    for mount in svc.get("volumes", []) or []:
        if mount.get("type") != "bind":
            continue
        source = mount.get("source", "")
        # The Docker socket is the one bind that names a path meaning the same thing everywhere:
        # it is the daemon's own endpoint, not repository or operator state.
        if source in ("/var/run/docker.sock", "/proc", "/sys"):
            continue
        if name in clone_only:
            print(f"check-compose-artifact: ok   {name} binds {source} and is declared clone-only")
            continue
        print(
            f"FAIL: service '{name}' binds the host path {source}. A remote install resolves a "
            "relative source against Compose's cache directory and a ${PWD} source against the "
            "operator's working directory; use a named volume, or add the service to "
            "CLONE_ONLY_SERVICES if it is profile-gated and documented as clone-only.",
            file=sys.stderr,
        )
        bad = 1
sys.exit(bad)
PY
[ "$fail" = 0 ] && echo "$P: ok   every default-profile mount is a named volume (or the docker socket)"

echo "$P: --- (2) publish's stricter decode: long-syntax ports, literal memory limits"
# Comments stripped first, so prose quoting either shape is not a hit.
_code() { sed -E 's/(^|[[:space:]])#.*$//' "$COMPOSE"; }
# A short-form port entry is a list item that is a bare scalar under `ports:`; the long form is a
# list item whose first key is `mode:`/`target:`.
short_ports="$(_code | awk '
    /^ *ports: *$/ {inp=1; next}
    inp && /^ *- / { if ($0 !~ /- *(mode|target|published|protocol|host_ip|name):/) { print NR": "$0 } ; next }
    inp && /^ *[a-z_]+: */ {inp=0}
' || true)"
if [ -n "$short_ports" ]; then
    echo "$P: RED  $COMPOSE has short-form ports: entries, which publish's decoder rejects:" >&2
    printf '%s\n' "$short_ports" | sed 's/^/      /' >&2
    fail=1
else
    echo "$P: ok   every ports: entry is long syntax"
fi
interp_mem="$(_code | grep -nE '^ *(mem_limit|mem_reservation|memory): *.*\$\{' || true)"
if [ -n "$interp_mem" ]; then
    echo "$P: RED  $COMPOSE interpolates a memory limit, which publish's decoder reads as a number:" >&2
    printf '%s\n' "$interp_mem" | sed 's/^/      /' >&2
    fail=1
else
    echo "$P: ok   no memory limit carries an interpolation"
fi

echo "$P: --- (3) the build strip removes build sections and nothing else"
# The stripped copy is written BESIDE the original, not into a temp directory: Compose resolves
# every relative path in a file against that file's own directory, so a copy elsewhere renders the
# alloy bind to a different absolute source and the parity assertion below fails on a difference
# the strip did not make.
STRIPPED="$ROOT/.compose-artifact-check.yml"
trap 'rm -f "$STRIPPED"' EXIT
python3 "$ROOT/scripts/lib/strip-compose-build.py" "$COMPOSE" "$STRIPPED" || fail=1
if [ -f "$STRIPPED" ]; then
    # Render both, drop `build` from the original's rendering, and require byte equality. This is
    # what makes a line-based strip safe: a stray removed line anywhere else shows up here.
    if ! python3 - "$COMPOSE" "$STRIPPED" <<'PY'; then fail=1; fi
import json, subprocess, sys

def render(path, drop_build):
    raw = subprocess.run(
        ["docker", "compose", "-f", path, "--profile", "*", "config", "--format", "json"],
        capture_output=True, text=True,
    )
    if raw.returncode != 0:
        print(f"FAIL: `docker compose config` could not render {path}:\n{raw.stderr}", file=sys.stderr)
        sys.exit(1)
    model = json.loads(raw.stdout)
    if drop_build:
        for svc in model.get("services", {}).values():
            svc.pop("build", None)
    return json.dumps(model, sort_keys=True, indent=2)

original, stripped = sys.argv[1], sys.argv[2]
a, b = render(original, drop_build=True), render(stripped, drop_build=False)
if a != b:
    print(
        "FAIL: the build strip changed more than the build sections. Rendered configs differ once "
        "`build` is removed from the original — scripts/lib/strip-compose-build.py's line-based "
        "edit no longer matches this file's shape.",
        file=sys.stderr,
    )
    import difflib
    for line in list(difflib.unified_diff(a.splitlines(), b.splitlines(), "original-minus-build", "stripped", lineterm=""))[:40]:
        print("  " + line, file=sys.stderr)
    sys.exit(1)
print("check-compose-artifact: ok   the strip removes build sections and nothing else")
PY
fi

echo "$P: --- (4) the release stamp pins every image and changes nothing else"
# The repository holds NO version literal — the git tag .github/workflows/release.yml pushes is the
# only source of truth, so docker-compose.yml's image defaults float to `${TESSARY_VERSION:-latest}`
# and scripts/publish-compose-artifact.sh stamps the release's own version in on the way out
# (scripts/lib/pin-compose-version.py). That stamp is the whole reason a floating repository file
# can still publish a PINNED artifact, which the one-command install requires, so it is checked here
# rather than trusted: run it with a probe version and require the rendered config to differ from
# the unpinned one in exactly the image references and nowhere else. A stamp that silently missed a
# service would otherwise publish a config with one floating image in it, and the ordering guarantee
# in release.yml's header would be false for that service alone.
PIN_PROBE=9.9.9
PINNED="$ROOT/.compose-artifact-check.pinned.yml"
trap 'rm -f "$STRIPPED" "$PINNED"' EXIT
if [ -f "$STRIPPED" ]; then
    python3 "$ROOT/scripts/lib/pin-compose-version.py" "$STRIPPED" "$PINNED" "$PIN_PROBE" || fail=1
fi
if [ -f "$PINNED" ]; then
    if ! python3 - "$STRIPPED" "$PINNED" "$PIN_PROBE" <<'PY'; then fail=1; fi
import json, subprocess, sys

def render(path):
    raw = subprocess.run(
        ["docker", "compose", "-f", path, "--profile", "*", "config", "--format", "json"],
        capture_output=True, text=True,
    )
    if raw.returncode != 0:
        print(f"FAIL: `docker compose config` could not render {path}:\n{raw.stderr}", file=sys.stderr)
        sys.exit(1)
    return json.dumps(json.loads(raw.stdout), sort_keys=True, indent=2)

unpinned_path, pinned_path, probe = sys.argv[1], sys.argv[2], sys.argv[3]
unpinned, pinned = render(unpinned_path), render(pinned_path)

# Every service the artifact names must be pinned, AGENT_IMAGE included — it is an env value rather
# than a compose `image:` key, so nothing else in this file would notice it staying floating.
missing = [
    service for service in ("backend", "frontend", "sandbox-runner", "agent-sandbox")
    if f"tessaryai/tessary:{service}-{probe}" not in pinned
]
if missing:
    print(
        f"FAIL: the release stamp left {', '.join(missing)} floating. Every published image "
        "reference must carry the release's version; pin-compose-version.py's substitution no "
        "longer matches how this file writes that service's default.",
        file=sys.stderr,
    )
    sys.exit(1)

if pinned.replace(f"-{probe}", "-latest") != unpinned:
    print(
        "FAIL: the release stamp changed more than the image versions. Rendered configs differ "
        "once the probe version is mapped back to `latest`.",
        file=sys.stderr,
    )
    import difflib
    for line in list(difflib.unified_diff(
        unpinned.splitlines(), pinned.replace(f"-{probe}", "-latest").splitlines(),
        "unpinned", "pinned-mapped-back", lineterm="",
    ))[:40]:
        print("  " + line, file=sys.stderr)
    sys.exit(1)
print("check-compose-artifact: ok   the stamp pins all four images and changes nothing else")
PY
fi

# Removed 2026-09-09 under the standing rule in scripts/check.sh's header: no gate reads a .md
# or .mdx file. The clause here asserted that setup.md, README.md and
# docs/self-hosting/setup.mdx each carry the published install command verbatim. What it protected
# is real -- the site's hero command and the docs' command drifting apart, with `-y` the thing that
# goes missing -- but a per-PR gate keyed on three prose files reds when somebody rewrites a
# paragraph. This gate now asserts the artifact's structure: long-syntax ports, no host binds in
# default-profile services, a clean build strip. All of that is YAML.

if [ "$NEGATIVE" = 1 ]; then
    echo "$P: --- negative: each of the three classes must be red"
    T="$(mktemp -d)"; trap 'rm -rf "$T"; rm -f "$STRIPPED"' EXIT
    mkdir -p "$T/scripts/lib"
    cp "$COMPOSE" "$T/$COMPOSE"
    cp "$ROOT/scripts/lib/strip-compose-build.py" "$T/scripts/lib/"
    _must_be_red() {
        if bash "$0" --root="$T" >/dev/null 2>&1; then
            echo "$P: FAIL, $1 passed; that assertion is vacuous" >&2; exit 1
        fi
        echo "$P: negative ok: $1 is red"
        cp "$COMPOSE" "$T/$COMPOSE"
    }
    python3 - "$T/$COMPOSE" <<'PY'
import sys
p = sys.argv[1]
s = open(p).read().replace("      - sandbox-work:/launcher-work", "      - ${PWD}/.data/sandbox-runner-work:/launcher-work", 1)
open(p, "w").write(s)
PY
    _must_be_red "a \${PWD} bind in a default-profile service"
    python3 - "$T/$COMPOSE" <<'PY'
import re, sys
p = sys.argv[1]
s = open(p).read().replace("""      - mode: ingress
        target: 8000
        published: "${HTTP_PORT:-80}"
        protocol: tcp""", '      - "${HTTP_PORT:-80}:8000"', 1)
open(p, "w").write(s)
PY
    _must_be_red "a short-form ports: entry"
    python3 - "$T/$COMPOSE" <<'PY'
import sys
p = sys.argv[1]
open(p, "w").write(open(p).read().replace("    mem_limit: 2g", "    mem_limit: ${BACKEND_MEM_LIMIT:-2g}", 1))
PY
    _must_be_red "an interpolated mem_limit"
fi

[ "$fail" = 0 ] || { echo "$P: FAIL" >&2; exit 1; }
echo "$P: ok, $COMPOSE is publishable as an OCI artifact and mounts nothing a remote install cannot resolve"
