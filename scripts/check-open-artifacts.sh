#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# The open-edition artifact diff (epic 6 clause 3, #1148): every image the open edition publishes
# is enumerated and diffed against scripts/lib/open-artifact-denylist.txt — no paid class, no
# overlay jar or directory, no paid Liquibase lane, no LaunchDarkly, no trained model weight, and
# no paid-only route in the served frontend bundle. This reads the BUILT ARTIFACT, never the source
# tree: the source-tree scrub proves the export carries no paid code and says nothing about a
# cache layer, a build argument, or a longer-lived branch that quietly re-includes a module. The
# 2026-09-05 pass proved the point the hard way — every source-side gate passed a planted paid
# stub, a re-added detector and a baked .onnx.
#
# Four images, the four release.yml publishes: backend, frontend, sandbox-runner, agent-sandbox.
# Each is exported with `docker create` + `docker export` (never run — an image that cannot start
# on this host is still an image whose contents can be listed) and walked by
# scripts/lib/open-artifact-contents.py, which prints what it found before it prints a verdict.
#
#   bash scripts/check-open-artifacts.sh                  build the four images here, then diff
#   bash scripts/check-open-artifacts.sh --images backend=<ref> frontend=<ref> \
#          sandbox-runner=<ref> agent-sandbox=<ref>       diff already-built images (release.yml)
#   bash scripts/check-open-artifacts.sh --negative       ALSO prove the diff goes red: a derived
#          image per role carrying one planted violation must FAIL (the overlay's own image check
#          additionally feeds the real paid backend image through --images and requires red)
#   bash scripts/check-open-artifacts.sh --derive         with OPEN_ARTIFACTS_PAID_SPEC naming the paid
#          OpenAPI spec (the Taskfile sets it where the overlay exists): red when that spec carries
#          a path the open spec lacks whose last segment no `text` rule names;
#          on its own it stops there, combined with --negative or --images it runs the diff too
#
# Needs Docker and minutes to build; EXCLUDED from `task check` (see scripts/check.sh's manifest).
# Run via `task check:open:artifacts`, and by release.yml before any manifest is merged or tagged.
set -euo pipefail
P=check-open-artifacts
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

NEGATIVE=0; DERIVE=0; declare -a REFS=()
while [ $# -gt 0 ]; do
    case "$1" in
        --negative) NEGATIVE=1 ;;
        --derive) DERIVE=1 ;;
        --images) shift; while [ $# -gt 0 ] && [[ "$1" != --* ]]; do REFS+=("$1"); shift; done; continue ;;
        *) echo "$P: unknown argument '$1' (accepts --images <role>=<ref>..., --negative, --derive)" >&2; exit 2 ;;
    esac
    shift
done

DENYLIST="$ROOT/scripts/lib/open-artifact-denylist.txt"
WALKER="$ROOT/scripts/lib/open-artifact-contents.py"
OPEN_MODULES="$(sed -n 's|.*<module>\(.*\)</module>.*|\1|p' backend/pom.xml | grep -v '^test-support$' | tr '\n' ',' | sed 's/,$//')"

if [ "$DERIVE" = 1 ]; then
    # The paid spec's location is the overlay's to know, not this script's: the Taskfile in a
    # checkout that has the overlay passes it in; a checkout without one has nothing to derive.
    PAID_SPEC="${OPEN_ARTIFACTS_PAID_SPEC:-}"
    OPEN_SPEC="$ROOT/backend/contract/src/main/resources/openapi/tessary-api.json"
    if [ -z "$PAID_SPEC" ] || [ ! -f "$PAID_SPEC" ]; then echo "$P: --derive needs OPEN_ARTIFACTS_PAID_SPEC to name the paid OpenAPI spec; none given, nothing to derive"; [ "$NEGATIVE" = 1 ] || [ "${#REFS[@]}" -gt 0 ] || exit 0; DERIVE=0; fi
  if [ "$DERIVE" = 1 ]; then
    fail=0
    patterns="$(mktemp)"; grep -E '^text\|' "$DENYLIST" | sed -E 's/^text\|//; s/\|[^|]*$//' > "$patterns"
    while read -r path; do
        [ -n "$path" ] || continue
        # A text rule is a regex, so coverage is judged on the path's last literal segment (trailing
        # {variables} dropped): a paid-only route whose final word appears in no rule is a route the
        # bundle scan cannot see.
        seg="$(sed -E 's#(/\{[^}]*\})+$##' <<<"$path")"; seg="${seg##*/}"
        grep -qF "$seg" "$patterns" \
            || { echo "$P: paid-only path $path: its last segment is named by no text rule in scripts/lib/open-artifact-denylist.txt" >&2; fail=1; }
    done < <(comm -23 <(jq -r '.paths|keys[]' "$PAID_SPEC" | sort) <(jq -r '.paths|keys[]' "$OPEN_SPEC" | sort))
    rm -f "$patterns"
    [ "$fail" = 0 ] || exit 1
    echo "$P: every paid-only route is covered by a text rule"
    [ "$NEGATIVE" = 1 ] || [ "${#REFS[@]}" -gt 0 ] || exit 0
  fi
fi

if [ -z "${PNPM_VERSION:-}" ]; then
    PNPM_VERSION="$(sed -n 's/^  PNPM_VERSION: "\(.*\)"$/\1/p' "$ROOT/Taskfile.yml" | head -1)"
fi
export PNPM_VERSION
VERSION="$(git rev-parse --short HEAD 2>/dev/null || echo dev)"

TMP="$(mktemp -d)"
declare -a CIDS=(); declare -a PLANTED=()
_cleanup() {
    local status=$?
    for c in "${CIDS[@]+"${CIDS[@]}"}"; do docker rm -f "$c" >/dev/null 2>&1 || true; done
    for i in "${PLANTED[@]+"${PLANTED[@]}"}"; do docker rmi -f "$i" >/dev/null 2>&1 || true; done
    rm -rf "$TMP"
    exit "$status"
}
trap _cleanup EXIT

# bash 3.2 (macOS) has no associative arrays: one variable per role.
# shellcheck disable=SC2034  # read indirectly by _img
IMG_backend="" IMG_frontend="" IMG_sandbox_runner="" IMG_agent_sandbox=""
_img() { local v="IMG_${1//-/_}"; printf '%s' "${!v}"; }
_set_img() { local v="IMG_${1//-/_}"; printf -v "$v" '%s' "$2"; }
if [ "${#REFS[@]}" -gt 0 ]; then
    for kv in "${REFS[@]}"; do
        case "$kv" in backend=*|frontend=*|sandbox-runner=*|agent-sandbox=*) _set_img "${kv%%=*}" "${kv#*=}" ;;
            *) echo "$P: --images takes <role>=<ref> with role one of backend|frontend|sandbox-runner|agent-sandbox, got '$kv'" >&2; exit 2 ;; esac
    done
else
    _set_img backend "tessary-backend:open-artifacts-$VERSION"
    _set_img frontend "tessary-frontend:open-artifacts-$VERSION"
    _set_img sandbox-runner "tessary-sandbox-runner:open-artifacts-$VERSION"
    _set_img agent-sandbox "tessary-agent-sandbox:open-artifacts-$VERSION"
    echo "$P: building the four open images (version $VERSION)"
    BACKEND_IMAGE="$(_img backend)" FRONTEND_IMAGE="$(_img frontend)" SANDBOX_RUNNER_IMAGE="$(_img sandbox-runner)" \
        IMAGE_VERSION="$VERSION" docker compose -f docker-compose.yml build -q backend frontend sandbox-runner
    docker build -q -f sandbox-runner/agent-sandbox/Dockerfile --build-arg IMAGE_VERSION="$VERSION" --build-arg PNPM_VERSION="$PNPM_VERSION" -t "$(_img agent-sandbox)" . >/dev/null
fi
# With --images, only the roles given are diffed (the overlay's image check hands in one image at a
# time); without it, all four are built and diffed.
ROLES=""
for role in backend frontend sandbox-runner agent-sandbox; do
    if [ -n "$(_img "$role")" ]; then ROLES="$ROLES $role"; elif [ "${#REFS[@]}" -eq 0 ]; then echo "$P: no image for $role" >&2; exit 2; fi
done
[ -n "$ROLES" ] || { echo "$P: --images named no role (backend|frontend|sandbox-runner|agent-sandbox)" >&2; exit 2; }

# $1 = role, $2 = image ref. Exports and walks; returns the walker's exit code.
_diff() {
    local role="$1" ref="$2" cid
    cid="$(docker create "$ref")"; CIDS+=("$cid")
    docker export "$cid" -o "$TMP/$role.tar"
    python3 "$WALKER" --role "$role" --tar "$TMP/$role.tar" --denylist "$DENYLIST" --open-modules "$OPEN_MODULES"
}

fail=0
for role in $ROLES; do
    echo "$P: --- $role ($(_img "$role"))"
    _diff "$role" "$(_img "$role")" || fail=1
done
[ "$fail" = 0 ] || { echo "$P: FAIL, an open artifact carries paid content (findings above)" >&2; exit 1; }
echo "$P: open artifacts clean against the deny list:$ROLES"

if [ "$NEGATIVE" = 1 ]; then
    echo "$P: --- negative: each role must go RED on one planted violation"
    _has() { [ -n "$(_img "$1")" ]; }
    # The overlay-carrying image itself is proven red by the overlay's own image check, the one
    # script allowed to name both editions; here the backend plant is the flag SDK only the paid
    # edition declares.
    if _has backend; then
        # A FAT jar: one paid class under BOOT-INF/classes/ and one LaunchDarkly jar under BOOT-INF/lib/,
        # both nested inside a single jar in /app/lib. Proves the walker sees inside a repackaged
        # boot jar, not only the extracted layout backend/Dockerfile produces today.
        python3 - "$TMP/planted.jar" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1], "w") as z:
    z.writestr("BOOT-INF/classes/ai/tessary/paid/Planted.class", b"\xca\xfe\xba\xbe")
    z.writestr("BOOT-INF/lib/launchdarkly-java-server-sdk-7.0.0.jar", b"PK")
PY
        printf 'FROM %s\nUSER root\nCOPY planted.jar /app/lib/planted.jar\n' "$(_img backend)" \
            | docker build -q -t "tessary-planted-backend:$VERSION" -f - "$TMP" >/dev/null
        PLANTED+=("tessary-planted-backend:$VERSION")
        if _diff backend "tessary-planted-backend:$VERSION" >/dev/null; then echo "$P: FAIL, a planted fat jar with a nested paid class and LaunchDarkly jar passed" >&2; exit 1; fi
        echo "$P: negative ok: a planted fat jar (nested paid class + LaunchDarkly jar) in the backend image is red"
    fi
    if _has frontend; then
        printf 'FROM %s\nUSER root\nRUN printf "fetch(\\"/conformance/fit-report\\")" > /srv/planted.js\n' "$(_img frontend)" \
            | docker build -q -t "tessary-planted-frontend:$VERSION" -f - . >/dev/null
        PLANTED+=("tessary-planted-frontend:$VERSION")
        if _diff frontend "tessary-planted-frontend:$VERSION" >/dev/null; then echo "$P: FAIL, a planted paid route in the bundle passed" >&2; exit 1; fi
        echo "$P: negative ok: a planted paid route in the frontend bundle is red"
    fi
    if _has sandbox-runner; then
        printf 'FROM %s\nUSER root\nRUN mkdir -p /app/paid && touch /app/paid/plan.jar\n' "$(_img sandbox-runner)" \
            | docker build -q -t "tessary-planted-sandbox-runner:$VERSION" -f - . >/dev/null
        PLANTED+=("tessary-planted-sandbox-runner:$VERSION")
        if _diff sandbox-runner "tessary-planted-sandbox-runner:$VERSION" >/dev/null; then echo "$P: FAIL, a planted overlay directory passed" >&2; exit 1; fi
        echo "$P: negative ok: a planted /app/paid directory in sandbox-runner is red"
    fi
    if _has agent-sandbox; then
        printf 'FROM %s\nUSER root\nRUN touch /home/user/frustration.onnx\n' "$(_img agent-sandbox)" \
            | docker build -q -t "tessary-planted-agent-sandbox:$VERSION" -f - . >/dev/null
        PLANTED+=("tessary-planted-agent-sandbox:$VERSION")
        if _diff agent-sandbox "tessary-planted-agent-sandbox:$VERSION" >/dev/null; then echo "$P: FAIL, a planted model weight passed" >&2; exit 1; fi
        echo "$P: negative ok: a planted model weight in agent-sandbox is red"
    fi
fi
echo "$P: ok"
